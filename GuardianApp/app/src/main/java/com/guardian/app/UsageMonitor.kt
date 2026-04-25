package com.guardian.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * 手机使用情况监控工具类。
 *
 * 使用 [UsageStatsManager] 查询过去 24 小时内的应用使用统计，
 * 找出 [getLastTimeUsed][android.app.usage.UsageStats.getLastTimeUsed] 最晚的条目，
 * 以此推断"手机最后一次被人主动使用"的时间。
 *
 * 权限说明：
 *  - [android.Manifest.permission.PACKAGE_USAGE_STATS] 是 AppOps 特殊权限，
 *    **无法通过 [requestPermissions][android.app.Activity.requestPermissions] 弹框申请**。
 *  - 需引导用户前往「设置 → 有权查看使用情况的应用」手动开启。
 *  - 调用前请先检查 [hasUsageStatsPermission]，权限未开启时返回 [Result.PermissionDenied]。
 */
class UsageMonitor(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // ── 返回值类型 ────────────────────────────────────────────

    sealed class Result {
        /**
         * 查询成功。
         * @param lastUsedAt 最后使用时间（Unix 毫秒时间戳），可直接转为 [java.util.Date] 或 ISO 8601 字符串上报。
         */
        data class Success(val lastUsedAt: Long) : Result()

        /** PACKAGE_USAGE_STATS 权限未开启，需引导用户在系统设置中授权 */
        object PermissionDenied : Result()

        /** 权限已开启，但过去 24 小时内无任何有效使用记录（极少见，通常发生在刚刷机后）*/
        object Unavailable : Result()
    }

    // ── 公开接口 ──────────────────────────────────────────────

    /**
     * 查询手机最后一次被使用的时间（同步，可在任意线程调用）。
     *
     * 逻辑：
     *  1. 检查 [hasUsageStatsPermission]，未授权直接返回 [Result.PermissionDenied]；
     *  2. 查询过去 24 小时内所有应用的 [UsageStats]；
     *  3. 过滤掉 `lastTimeUsed == 0` 的条目（从未记录过使用）；
     *  4. 取 [getLastTimeUsed][android.app.usage.UsageStats.getLastTimeUsed] 最大值返回。
     *
     * @return [Result.Success]       包含毫秒级时间戳
     *         [Result.PermissionDenied] PACKAGE_USAGE_STATS 未授权
     *         [Result.Unavailable]   无有效记录
     */
    fun getLastUsedTime(): Result {
        if (!hasUsageStatsPermission()) return Result.PermissionDenied

        val endTime   = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(24)

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        )

        if (stats.isNullOrEmpty()) return Result.Unavailable

        val lastUsedAt = stats
            .filter { it.lastTimeUsed > 0L }        // 排除从未被系统记录使用的条目
            .maxOfOrNull { it.lastTimeUsed }
            ?: return Result.Unavailable

        return Result.Success(lastUsedAt)
    }

    /**
     * 检查 [android.Manifest.permission.PACKAGE_USAGE_STATS] 是否已被用户授予。
     *
     * 内部使用 [AppOpsManager] 进行检测（API 29+ 使用 [AppOpsManager.unsafeCheckOpNoThrow]，
     * 低版本使用已废弃的 [AppOpsManager.checkOpNoThrow]，均安全）。
     *
     * 可在上报前调用，以便决定是否弹出引导 Dialog。
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
