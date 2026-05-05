package com.guardian.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 设备角色枚举。
 * [value] 与后端 API 及数据库中使用的字符串严格一致。
 */
enum class DeviceRole(val value: String) {
    GUARDIAN("guardian"),   // 监护者
    WARD("ward");           // 被监护者

    companion object {
        /** 从后端字符串反查枚举（未知值返回 null） */
        fun fromValue(value: String): DeviceRole? =
            entries.find { it.value == value }
    }
}

/**
 * 本地持久化管理器（SharedPreferences 封装）。
 *
 * 职责：
 *  1. 懒生成并缓存本机设备 UUID —— 首次访问时写入，此后只读，保证全生命周期唯一。
 *  2. 持久化设备角色（[DeviceRole.GUARDIAN] / [DeviceRole.WARD]）。
 *
 * 用法示例：
 * ```kotlin
 * val tm = TokenManager(context)
 * val uuid = tm.deviceUuid            // 始终非空
 * tm.deviceRole = DeviceRole.WARD     // 持久化角色选择
 * if (tm.hasRole()) { ... }
 * ```
 *
 * 注意：请使用 `context.applicationContext` 传入，避免 Activity 内存泄漏。
 */
class TokenManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── UUID ──────────────────────────────────────────────────

    /**
     * 本机设备唯一标识符。
     * 首次访问时自动生成（crypto-random UUID v4）并写入 SharedPreferences，
     * 后续访问直接读取缓存值，不再重新生成。
     */
    val deviceUuid: String
        get() {
            val stored = prefs.getString(KEY_UUID, null)
            if (stored != null) return stored
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, generated).apply()
            return generated
        }

    // ── 角色 ──────────────────────────────────────────────────

    /**
     * 当前设备角色；尚未选择时为 null。
     * 写入后立即持久化（apply() 异步提交）。
     */
    var deviceRole: DeviceRole?
        get() = prefs.getString(KEY_ROLE, null)?.let { DeviceRole.fromValue(it) }
        set(value) {
            prefs.edit().putString(KEY_ROLE, value?.value).apply()
        }

    // ── 状态查询 ──────────────────────────────────────────────

    /** 是否已完成角色选择 */
    fun hasRole(): Boolean = prefs.contains(KEY_ROLE)

    /** 当前角色是否为监护者 */
    fun isGuardian(): Boolean = deviceRole == DeviceRole.GUARDIAN

    /** 当前角色是否为被监护者 */
    fun isWard(): Boolean = deviceRole == DeviceRole.WARD

    // ── 重置 ──────────────────────────────────────────────────

    // ── JWT Token ─────────────────────────────────────────────

    /**
     * 服务端注册成功后下发的 JWT token。
     * 所有受保护接口的请求头 `Authorization: Bearer <token>` 均来自此字段。
     * 未完成注册时为 null，ApiClient 会跳过添加 header。
     */
    var authToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    // ── 重置 ──────────────────────────────────────────────────

    /**
     * 清除所有本地状态（角色 + UUID + token）。
     * 适用于解绑 / 出厂重置场景；下次访问 [deviceUuid] 将重新生成。
     */
    fun clear() = prefs.edit().clear().apply()

    // ── 常量 ──────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "guardian_prefs"
        private const val KEY_UUID   = "device_uuid"
        private const val KEY_ROLE   = "device_role"
        private const val KEY_TOKEN  = "auth_token"
    }
}
