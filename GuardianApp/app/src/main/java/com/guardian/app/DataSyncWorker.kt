package com.guardian.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.guardian.app.network.ApiClient
import com.guardian.app.network.DataUpdateRequest
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 被监护者（Ward）后台数据同步 Worker。
 *
 * 每次执行流程：
 *  1. 角色校验 —— 非 Ward 设备直接跳过（不报错）
 *  2. 读取本机 UUID
 *  3. 调用 [LocationTracker] 获取当前经纬度
 *  4. 调用 [UsageMonitor] 获取手机最后使用时间
 *  5. 通过 [ApiClient] POST 到后端 `/data/update`
 *
 * 使用 [CoroutineWorker]，[LocationTracker.getLocation] 的挂起调用可直接在 [doWork] 中使用。
 *
 * 调度方式：在 Application.onCreate() 中调用 [schedule]，WorkManager 保证唯一任务不重复注册。
 */
class DataSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager(appContext)

        // ── Step 0: 角色校验 ──────────────────────────────────
        // 只有被监护者需要主动上报数据，监护者跳过
        if (!tokenManager.isWard()) {
            Log.d(TAG, "Role is not WARD, skipping sync")
            return Result.success()
        }

        val uuid = tokenManager.deviceUuid

        // ── Step 1: 获取位置 ──────────────────────────────────
        val (latitude, longitude) = when (val loc = LocationTracker(appContext).getLocation()) {
            is LocationTracker.Result.Success -> {
                Log.d(TAG, "Location acquired: ${loc.latitude}, ${loc.longitude}")
                Pair(loc.latitude, loc.longitude)
            }
            is LocationTracker.Result.PermissionDenied -> {
                // 权限问题不会自动恢复，不重试，等待用户手动授权后下次触发
                Log.w(TAG, "Location permission denied, skipping sync")
                return Result.failure(workDataOf(KEY_ERROR to "location_permission_denied"))
            }
            is LocationTracker.Result.Unavailable -> {
                // GPS 暂时不可用（如室内、飞行模式），触发重试等待下次窗口
                Log.w(TAG, "Location unavailable, will retry")
                return Result.retry()
            }
        }

        // ── Step 2: 获取手机最后使用时间 ──────────────────────
        val lastUsedAt: Long = when (val usage = UsageMonitor(appContext).getLastUsedTime()) {
            is UsageMonitor.Result.Success -> {
                Log.d(TAG, "Last used at: ${usage.lastUsedAt}")
                usage.lastUsedAt
            }
            is UsageMonitor.Result.PermissionDenied -> {
                // PACKAGE_USAGE_STATS 需用户手动开启，回退到当前时间继续上报位置数据
                Log.w(TAG, "Usage stats permission not granted, falling back to current time")
                System.currentTimeMillis()
            }
            is UsageMonitor.Result.Unavailable -> {
                // 过去 24h 无记录（极少见），同样回退
                Log.w(TAG, "Usage stats unavailable, falling back to current time")
                System.currentTimeMillis()
            }
        }

        // ── Step 3: 格式化时间戳 → ISO 8601（后端 time.Time 解析 RFC3339）──
        val lastUsedAtIso = DateTimeFormatter.ISO_INSTANT
            .format(Instant.ofEpochMilli(lastUsedAt))

        // ── Step 4: 上报数据 ──────────────────────────────────
        return try {
            // uuid 由服务端从 JWT token 中读取，无需在 body 中传递
            ApiClient.api.updateData(
                DataUpdateRequest(
                    lat        = latitude,
                    lng        = longitude,
                    lastUsedAt = lastUsedAtIso
                )
            )
            Log.i(TAG, "Sync OK — uuid=$uuid lat=$latitude lng=$longitude lastUsed=$lastUsedAtIso")
            Result.success()

        } catch (e: HttpException) {
            when (e.code()) {
                403 -> {
                    // 设备尚未完成配对，后端拒绝上报；这是预期状态，不报错，等配对完成后自然恢复
                    Log.w(TAG, "Not paired yet (403), sync skipped")
                    Result.success()
                }
                in 500..599 -> {
                    // 服务端暂时错误，触发重试
                    Log.e(TAG, "Server error ${e.code()}, will retry")
                    Result.retry()
                }
                else -> {
                    // 其他 4xx：请求本身有问题，不重试
                    Log.e(TAG, "HTTP ${e.code()}, giving up: ${e.message()}")
                    Result.failure(workDataOf(KEY_ERROR to "http_${e.code()}"))
                }
            }
        } catch (e: IOException) {
            // 网络断开、超时等瞬时错误，触发重试
            Log.w(TAG, "Network error, will retry: ${e.message}")
            Result.retry()
        }
    }

    // ─────────────────────────────────────────────────────────
    // 调度管理
    // ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG       = "DataSyncWorker"
        private const val WORK_NAME = "guardian_data_sync"
        private const val KEY_ERROR = "error"

        /**
         * 注册（或确认已存在）周期性数据同步任务。
         *
         * 建议在 `Application.onCreate()` 中调用：
         * ```kotlin
         * class GuardianApplication : Application() {
         *     override fun onCreate() {
         *         super.onCreate()
         *         DataSyncWorker.schedule(this)
         *     }
         * }
         * ```
         *
         * 策略说明：
         * - 间隔 15 分钟（WorkManager 允许的最小周期）
         * - 要求网络可用（[NetworkType.CONNECTED]）；无网络时任务进入等待队列，
         *   网络恢复后自动执行，不会遗漏
         * - [ExistingPeriodicWorkPolicy.KEEP]：若同名任务已存在且未到期则保留，
         *   避免应用每次启动都重置计时器
         * - 重试退避：线性策略，初始间隔 30 秒，每次失败后线性递增
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DataSyncWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30_000L,                // 初始退避 30 秒
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Periodic sync scheduled (interval=15min, policy=KEEP)")
        }

        /**
         * 取消周期性同步任务。
         * 在解绑、角色切换或用户主动停止监控时调用。
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Periodic sync cancelled")
        }
    }
}
