package com.guardian.app.network

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────
// 请求 / 响应数据模型（字段名与后端 JSON key 一一对应）
// ─────────────────────────────────────────────────────────────

// POST /devices/register
data class RegisterRequest(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("role") val role: String        // "guardian" | "ward"
)

data class DeviceResponse(
    @SerializedName("uuid")         val uuid: String,
    @SerializedName("role")         val role: String,
    @SerializedName("latitude")     val latitude: Double,
    @SerializedName("longitude")    val longitude: Double,
    @SerializedName("last_seen")    val lastSeen: String,
    @SerializedName("last_used_at") val lastUsedAt: String?
)

// POST /pair/generate  ── 被监护者（ward）调用
data class GeneratePairingRequest(
    @SerializedName("ward_uuid") val wardUuid: String
)

data class GeneratePairingResponse(
    @SerializedName("pairing_code") val pairingCode: String,
    @SerializedName("expires_at")   val expiresAt: String       // ISO 8601
)

// POST /pair/confirm  ── 监护者（guardian）调用
data class ConfirmPairingRequest(
    @SerializedName("guardian_uuid") val guardianUuid: String,
    @SerializedName("pairing_code")  val pairingCode: String
)

data class BindingResponse(
    @SerializedName("guardian_id") val guardianId: String,
    @SerializedName("ward_id")     val wardId: String,
    @SerializedName("created_at")  val createdAt: String
)

// GET /pair/status?uuid=...
data class PairingStatusResponse(
    @SerializedName("paired")  val paired: Boolean,
    @SerializedName("binding") val binding: BindingResponse?    // paired=false 时为 null
)

// POST /data/update  ── 被监护者上报位置和手机最后使用时间
data class DataUpdateRequest(
    @SerializedName("uuid")         val uuid: String,
    @SerializedName("lat")          val lat: Double,
    @SerializedName("lng")          val lng: Double,
    @SerializedName("last_used_at") val lastUsedAt: String      // ISO 8601, e.g. "2025-04-25T08:00:00Z"
)

data class DataUpdateResponse(
    @SerializedName("message") val message: String
)

// GET /data/latest?uuid=<guardian_uuid>  ── 监护者查询被监护者最新状态
data class DataLatestResponse(
    @SerializedName("ward_uuid")    val wardUuid: String,
    @SerializedName("latitude")     val latitude: Double,
    @SerializedName("longitude")    val longitude: Double,
    @SerializedName("last_seen")    val lastSeen: String,
    @SerializedName("last_used_at") val lastUsedAt: String?     // 被监护者从未上报时为 null
)

// ─────────────────────────────────────────────────────────────
// Retrofit 接口声明
// ─────────────────────────────────────────────────────────────

interface GuardianApi {

    /** 设备首次启动时注册，幂等（重复注册同一 uuid 覆盖写入） */
    @POST("devices/register")
    suspend fun registerDevice(@Body request: RegisterRequest): DeviceResponse

    /** 被监护者生成 6 位配对码（有效期 5 分钟） */
    @POST("pair/generate")
    suspend fun generatePairing(@Body request: GeneratePairingRequest): GeneratePairingResponse

    /** 监护者输入配对码完成双向绑定 */
    @POST("pair/confirm")
    suspend fun confirmPairing(@Body request: ConfirmPairingRequest): BindingResponse

    /** 查询任意设备当前配对状态 */
    @GET("pair/status")
    suspend fun getPairingStatus(@Query("uuid") uuid: String): PairingStatusResponse

    /** 被监护者上报位置 + 手机最后使用时间（需已配对） */
    @POST("data/update")
    suspend fun updateData(@Body request: DataUpdateRequest): DataUpdateResponse

    /** 监护者拉取被监护者最新状态（需已配对） */
    @GET("data/latest")
    suspend fun getLatestData(@Query("uuid") guardianUuid: String): DataLatestResponse
}

// ─────────────────────────────────────────────────────────────
// Retrofit 单例
// ─────────────────────────────────────────────────────────────

object ApiClient {

    /**
     * 服务器基础地址。
     *  - 模拟器内访问宿主机 localhost → 10.0.2.2
     *  - 真机调试时改为宿主机局域网 IP，例如 "http://192.168.1.100:8080/"
     */
    const val BASE_URL = "http://10.0.2.2:8080/"

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    val api: GuardianApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GuardianApi::class.java)
    }
}
