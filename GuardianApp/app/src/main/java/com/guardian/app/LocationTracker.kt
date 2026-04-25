package com.guardian.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 单次位置获取工具类（不持续监听，不持有任何 callback 注册）。
 *
 * 使用 [FusedLocationProviderClient]，策略如下：
 *  1. 优先调用 [getCurrentLocation][com.google.android.gms.location.FusedLocationProviderClient.getCurrentLocation]
 *     强制触发一次新定位；
 *  2. 若返回 null（GPS 短时间内未能定位），回退读取
 *     [lastLocation][com.google.android.gms.location.FusedLocationProviderClient.lastLocation] 缓存。
 *
 * 所有操作封装为挂起函数，可直接在协程 / ViewModel 中调用，无需手动管理线程。
 */
class LocationTracker(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    // ── 返回值类型 ────────────────────────────────────────────

    sealed class Result {
        /** 定位成功 */
        data class Success(val latitude: Double, val longitude: Double) : Result()

        /** 缺少 ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION 权限 */
        object PermissionDenied : Result()

        /**
         * 权限已有，但无法获得坐标。
         * 常见原因：GPS 冷启动未就绪、飞行模式、设备位置服务已关闭，
         * 且系统也没有任何位置缓存。
         */
        object Unavailable : Result()
    }

    // ── 公开接口 ──────────────────────────────────────────────

    /**
     * 获取设备当前最新位置（挂起函数）。
     *
     * @return [Result.Success]       定位成功，包含经纬度
     *         [Result.PermissionDenied] 位置权限未授予
     *         [Result.Unavailable]   定位服务不可用或缓存为空
     */
    suspend fun getLocation(): Result {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) return Result.PermissionDenied

        // 精确位置可用时使用高精度模式，否则降级为网络/基站定位
        val priority = if (fineGranted) Priority.PRIORITY_HIGH_ACCURACY
                       else             Priority.PRIORITY_BALANCED_POWER_ACCURACY

        return try {
            val location = fetchCurrentLocation(priority) ?: fetchLastKnownLocation()
            if (location != null) Result.Success(location.latitude, location.longitude)
            else Result.Unavailable
        } catch (_: SecurityException) {
            // 权限在检查之后被系统撤销的极端情况（通常发生在锁定设备后权限被修改）
            Result.PermissionDenied
        }
    }

    // ── 私有实现 ──────────────────────────────────────────────

    /**
     * 调用 [FusedLocationProviderClient.getCurrentLocation] 强制触发定位。
     * 若协程被取消，同步取消底层 Task，避免 location request 泄漏。
     */
    private suspend fun fetchCurrentLocation(priority: Int): Location? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }

            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /**
     * 读取 FusedLocationProviderClient 的最后已知位置缓存（快速，但可能陈旧）。
     * 仅在 [fetchCurrentLocation] 返回 null 时作为回退使用。
     */
    private suspend fun fetchLastKnownLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
