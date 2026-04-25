package com.guardian.app

import android.app.Application
import android.util.Log

/**
 * 自定义 Application 类，负责应用级别的初始化。
 *
 * 在 AndroidManifest.xml 中已通过 android:name=".GuardianApplication" 注册。
 */
class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 仅对被监护者设备安排周期性同步；
        // 内部使用 ExistingPeriodicWorkPolicy.KEEP，重复调用安全幂等。
        val role = TokenManager(this).deviceRole
        if (role == DeviceRole.WARD) {
            DataSyncWorker.schedule(this)
            Log.i(TAG, "DataSyncWorker scheduled for WARD device")
        } else {
            Log.d(TAG, "Role=$role, DataSyncWorker not scheduled")
        }
    }

    companion object {
        private const val TAG = "GuardianApplication"
    }
}
