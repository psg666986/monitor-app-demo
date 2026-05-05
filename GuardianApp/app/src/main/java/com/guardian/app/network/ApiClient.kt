package com.guardian.app.network

import com.guardian.app.BuildConfig
import com.guardian.app.TokenManager
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────
// 请求 / 响应数据模型
// ─────────────────────────────────────────────────────────────

// POST /devices/register（公开路由）
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

// 注册响应：包含设备信息和 JWT token
data class RegisterResponse(
    @SerializedName("device") val device: DeviceResponse,
    @SerializedName("token")  val token: String
)

// POST /pair/generate  ── 被监护者（ward）调用，无 request body（UUID 从 JWT 取）
data class GeneratePairingResponse(
    @SerializedName("pairing_code") val pairingCode: String,
    @SerializedName("expires_at")   val expiresAt: String       // ISO 8601
)

// POST /pair/confirm  ── 监护者（guardian）调用，只需传配对码
data class ConfirmPairingRequest(
    @SerializedName("pairing_code") val pairingCode: String
)

data class BindingResponse(
    @SerializedName("guardian_id") val guardianId: String,
    @SerializedName("ward_id")     val wardId: String,
    @SerializedName("created_at")  val createdAt: String
)

// GET /pair/status  ── UUID 从 JWT 取，无需 query param
data class PairingStatusResponse(
    @SerializedName("paired")  val paired: Boolean,
    @SerializedName("binding") val binding: BindingResponse?
)

// POST /data/update  ── UUID 从 JWT 取，body 只含位置和使用时间
data class DataUpdateRequest(
    @SerializedName("lat")          val lat: Double,
    @SerializedName("lng")          val lng: Double,
    @SerializedName("last_used_at") val lastUsedAt: String      // ISO 8601
)

data class DataUpdateResponse(
    @SerializedName("message") val message: String
)

// GET /data/latest  ── guardian UUID 从 JWT 取，无需 query param
data class DataLatestResponse(
    @SerializedName("ward_uuid")    val wardUuid: String,
    @SerializedName("latitude")     val latitude: Double,
    @SerializedName("longitude")    val longitude: Double,
    @SerializedName("last_seen")    val lastSeen: String,
    @SerializedName("last_used_at") val lastUsedAt: String?
)

// ─────────────────────────────────────────────────────────────
// Retrofit 接口声明
// ─────────────────────────────────────────────────────────────

interface GuardianApi {

    /** 设备首次启动时注册，幂等（重复注册同一 uuid 覆盖写入），返回 JWT token */
    @POST("devices/register")
    suspend fun registerDevice(@Body request: RegisterRequest): RegisterResponse

    /** 被监护者生成 6 位配对码（有效期 5 分钟），UUID 从 JWT 获取，无需 body */
    @POST("pair/generate")
    suspend fun generatePairing(): GeneratePairingResponse

    /** 监护者输入配对码完成双向绑定 */
    @POST("pair/confirm")
    suspend fun confirmPairing(@Body request: ConfirmPairingRequest): BindingResponse

    /** 查询当前认证设备的配对状态，UUID 从 JWT 获取 */
    @GET("pair/status")
    suspend fun getPairingStatus(): PairingStatusResponse

    /** 被监护者上报位置 + 手机最后使用时间（UUID 从 JWT 获取） */
    @POST("data/update")
    suspend fun updateData(@Body request: DataUpdateRequest): DataUpdateResponse

    /** 监护者拉取被监护者最新状态（guardian UUID 从 JWT 获取） */
    @GET("data/latest")
    suspend fun getLatestData(): DataLatestResponse

    /** 任意一方解除配对关系（UUID 从 JWT 获取，幂等） */
    @DELETE("pair/binding")
    suspend fun unbindPairing(): MessageResponse
}

data class MessageResponse(
    @SerializedName("message") val message: String
)

// ─────────────────────────────────────────────────────────────
// Retrofit 单例
// ─────────────────────────────────────────────────────────────

object ApiClient {

    val BASE_URL: String get() = BuildConfig.SERVER_URL

    private lateinit var tokenManager: TokenManager

    /**
     * 在 Application.onCreate() 中调用，传入 TokenManager 以便认证拦截器读取 token。
     * 必须在首次使用 [api] 之前调用，否则抛出 [UninitializedPropertyAccessException]。
     */
    fun init(tm: TokenManager) {
        tokenManager = tm
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 认证拦截器：自动附加 Bearer token（token 为 null 时跳过）
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    tokenManager.authToken?.let { addHeader("Authorization", "Bearer $it") }
                }.build()
                chain.proceed(request)
            }
            // 日志拦截器：仅 debug 包开启 BODY 级别，避免 GPS 坐标泄漏到生产日志
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                        else HttpLoggingInterceptor.Level.NONE
            })
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
