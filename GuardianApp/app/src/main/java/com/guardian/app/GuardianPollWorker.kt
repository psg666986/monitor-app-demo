package com.guardian.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guardian.app.network.ApiClient
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 监护者（Guardian）后台轮询 Worker。
 *
 * 每次执行流程：
 *  1. 角色校验 —— 非 Guardian 设备直接跳过
 *  2. 调用 [ApiClient] GET `/data/latest` 拉取被监护者最新状态
 *  3. 发送本地通知，展示被监护者位置与最后使用手机时间
 *
 * 调度方式：在 [GuardianApplication.onCreate] 中调用 [schedule]。
 */
class GuardianPollWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager(appContext)

        if (!tokenManager.isGuardian()) {
            Log.d(TAG, "Role is not GUARDIAN, skipping poll")
            return Result.success()
        }

        return try {
            val data = ApiClient.api.getLatestData()

            // 计算距离最后使用手机的相对时间
            val lastUsedRelative = data.lastUsedAt
                ?.let { relativeTime(parseIso(it)) }
                ?: "暂无记录"

            val lastSeenRelative = relativeTime(parseIso(data.lastSeen))

            val content = "最后使用手机：$lastUsedRelative · " +
                    "位置：(${String.format("%.2f", data.latitude)}, " +
                    "${String.format("%.2f", data.longitude)})"

            sendNotification(lastSeenRelative, content)
            Log.i(TAG, "Poll OK — $content")
            Result.success()

        } catch (e: HttpException) {
            when (e.code()) {
                401 -> {
                    Log.w(TAG, "Unauthorized (401), token may be invalid")
                    Result.failure()
                }
                403, 404 -> {
                    // 尚未完成配对，正常状态，不通知
                    Log.d(TAG, "Not paired yet (${e.code()}), skipping notification")
                    Result.success()
                }
                in 500..599 -> {
                    Log.e(TAG, "Server error ${e.code()}, will retry")
                    Result.retry()
                }
                else -> Result.failure()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error, will retry: ${e.message}")
            Result.retry()
        }
    }

    // ── 通知 ──────────────────────────────────────────────────

    private fun sendNotification(title: String, content: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("监护者 · 同步于 $title")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    // ── 时间格式化 ────────────────────────────────────────────

    private fun parseIso(iso: String): Long {
        return try {
            // 解析 RFC3339 / ISO 8601 格式（Go 默认输出）
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            sdf.parse(iso)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    /** 将绝对时间戳转为"X 分钟前"/"刚刚"等相对描述 */
    private fun relativeTime(epochMs: Long): String {
        val diffMs = abs(System.currentTimeMillis() - epochMs)
        val diffMin = diffMs / 60_000
        return when {
            diffMin < 1   -> "刚刚"
            diffMin < 60  -> "${diffMin} 分钟前"
            diffMin < 1440 -> "${diffMin / 60} 小时前"
            else           -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))
        }
    }

    // ── 调度管理 ──────────────────────────────────────────────

    companion object {
        private const val TAG           = "GuardianPollWorker"
        private const val WORK_NAME     = "guardian_poll"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID            = "guardian_poll"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<GuardianPollWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic poll scheduled (interval=15min, policy=KEEP)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Periodic poll cancelled")
        }
    }
}
