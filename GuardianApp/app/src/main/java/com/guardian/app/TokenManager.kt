package com.guardian.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
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
 * 本地持久化管理器。
 *
 * 敏感数据（UUID、JWT token、角色）存储于 [EncryptedSharedPreferences]，
 * 由系统 KeyStore 的 AES-256-GCM 主密钥加密，root 用户仍无法直接读取明文。
 *
 * 非敏感元数据（同步时间戳）存储于普通 SharedPreferences（性能较好，内容不涉及隐私）。
 */
class TokenManager(context: Context) {

    private val ctx = context.applicationContext

    // ── 加密存储（敏感数据）──────────────────────────────────

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── 非敏感元数据存储（同步时间戳等）──────────────────────

    private val metaPrefs: SharedPreferences =
        ctx.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)

    // ── UUID ──────────────────────────────────────────────────

    /**
     * 本机设备唯一标识符（UUID v4，懒生成并永久持久化）。
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

    var deviceRole: DeviceRole?
        get() = prefs.getString(KEY_ROLE, null)?.let { DeviceRole.fromValue(it) }
        set(value) { prefs.edit().putString(KEY_ROLE, value?.value).apply() }

    // ── JWT Token ─────────────────────────────────────────────

    var authToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    // ── 非敏感元数据 ──────────────────────────────────────────

    /**
     * Ward 最后一次成功同步到服务器的 Unix 时间戳（毫秒）。
     * 由 DataSyncWorker 在成功上报后写入；主界面用于展示"上次同步：X 分钟前"。
     */
    var lastSyncTime: Long
        get() = metaPrefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) { metaPrefs.edit().putLong(KEY_LAST_SYNC, value).apply() }

    // ── 状态查询 ──────────────────────────────────────────────

    fun hasRole(): Boolean = prefs.contains(KEY_ROLE)
    fun isGuardian(): Boolean = deviceRole == DeviceRole.GUARDIAN
    fun isWard(): Boolean = deviceRole == DeviceRole.WARD

    /**
     * 解码 JWT payload 并检查 `exp` 字段是否已过当前时间。
     * 无 token 或解码失败均视为已过期，触发重新注册流程。
     */
    fun isTokenExpired(): Boolean {
        val token = authToken ?: return true
        return try {
            val payloadB64 = token.split(".").getOrNull(1) ?: return true
            val decoded = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING)
            val json = JSONObject(String(decoded))
            val exp = json.getLong("exp")
            System.currentTimeMillis() / 1000 >= exp
        } catch (_: Exception) { true }
    }

    // ── 重置 ──────────────────────────────────────────────────

    /**
     * 清除所有本地状态（UUID、角色、token、同步时间戳）。
     * 适用于完整重置场景；下次访问 [deviceUuid] 将重新生成。
     */
    fun clear() {
        prefs.edit().clear().apply()
        metaPrefs.edit().clear().apply()
    }

    // ── 常量 ──────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME      = "guardian_secure_prefs"  // EncryptedSharedPreferences
        private const val META_PREFS_NAME = "guardian_meta"           // 普通 SharedPreferences
        private const val KEY_UUID        = "device_uuid"
        private const val KEY_ROLE        = "device_role"
        private const val KEY_TOKEN       = "auth_token"
        private const val KEY_LAST_SYNC   = "last_sync_time"
    }
}
