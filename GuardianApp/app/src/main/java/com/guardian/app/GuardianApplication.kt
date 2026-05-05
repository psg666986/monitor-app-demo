package com.guardian.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.guardian.app.network.ApiClient

/**
 * 自定义 Application 类，负责应用级别的初始化。
 *
 * 在 AndroidManifest.xml 中已通过 android:name=".GuardianApplication" 注册。
 */
class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val tokenManager = TokenManager(this)

        // 初始化 ApiClient，注入 TokenManager 以支持认证拦截器自动附加 Bearer token
        ApiClient.init(tokenManager)

        // 创建通知渠道（API 26+ 必须，低版本自动忽略）
        createNotificationChannels()

        when (tokenManager.deviceRole) {
            DeviceRole.WARD -> {
                // 被监护者：启动周期性位置和使用时间上报
                DataSyncWorker.schedule(this)
                Log.i(TAG, "DataSyncWorker scheduled for WARD device")
            }
            DeviceRole.GUARDIAN -> {
                // 监护者：启动周期性数据拉取和本地通知
                GuardianPollWorker.schedule(this)
                Log.i(TAG, "GuardianPollWorker scheduled for GUARDIAN device")
            }
            null -> {
                Log.d(TAG, "Role not set, skipping worker scheduling")
            }
        }
    }

    /**
     * 创建所有通知渠道。
     * 渠道一旦创建不可修改，只能由用户在系统设置中调整。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java)

        // 监护者轮询通知渠道
        val pollChannel = NotificationChannel(
            GuardianPollWorker.CHANNEL_ID,
            "监护者状态提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "定期推送被监护者的位置和手机使用状态"
        }
        nm.createNotificationChannel(pollChannel)
    }

    companion object {
        private const val TAG = "GuardianApplication"
    }
}
