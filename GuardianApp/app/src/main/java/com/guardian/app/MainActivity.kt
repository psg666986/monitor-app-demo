package com.guardian.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardian.app.network.ApiClient
import com.guardian.app.network.ConfirmPairingRequest
import com.guardian.app.network.GeneratePairingRequest
import com.guardian.app.network.RegisterRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 调试专用 Activity：无 XML 布局，所有输出走 Logcat。
 *
 * 启动后按顺序执行：
 *  1. 动态申请位置权限（前台 → 后台，分两步）
 *  2. 检测 PACKAGE_USAGE_STATS 权限，未授权时跳转系统设置
 *  3. 打印本机 UUID 和当前角色
 *
 * 三个测试按钮分别对应 [testGenerateCode] / [testConfirmCode] / [testFetchData]，
 * 所有结果均通过 `Log.i(TAG, ...)` 输出，用 Logcat 过滤 TAG="MainActivity" 查看。
 */
class MainActivity : Activity() {

    // ── 协程作用域（与 Activity 生命周期绑定）────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val tokenManager by lazy { TokenManager(this) }

    // 配对码输入框（在 buildLayout() 中初始化）
    private lateinit var codeInput: EditText

    // ── 生命周期 ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        // Step 1: 打印设备信息
        logDeviceInfo()

        // Step 2: 申请位置权限（前台部分；后台位置在回调中追加）
        requestForegroundLocation()

        // Step 3: 检测使用情况统计权限
        checkUsageStatsPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()          // 避免 Activity 销毁后协程泄漏
    }

    // ── 极简 UI（全程序构建，无 XML）────────────────────────

    private fun buildLayout(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 32.dp(), 20.dp(), 32.dp())
        }

        // 标题
        root.addView(TextView(this).apply {
            text = "Guardian Debug Console"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24.dp())
        })

        // 分隔说明
        root.addView(label("─── Ward 端操作 ───"))

        // 按钮 1：被监护者生成配对码
        root.addView(button("[Ward] 生成配对码 & 启动同步") {
            testGenerateCode()
        })

        root.addView(label("─── Guardian 端操作 ───"))

        // 配对码输入框
        codeInput = EditText(this).apply {
            hint = "输入6位配对码..."
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(codeInput)

        // 按钮 2：监护者确认配对码
        root.addView(button("[Guardian] 确认配对码") {
            val code = codeInput.text.toString().trim()
            if (code.length == 6) {
                testConfirmCode(code)
            } else {
                Log.w(TAG, "请先输入6位配对码")
            }
        })

        // 按钮 3：监护者拉取被监护者数据
        root.addView(button("[Guardian] 拉取最新数据") {
            testFetchData()
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 16.dp(), 0, 8.dp())
        setTextColor(0xFF888888.toInt())
    }

    private fun Int.dp() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8.dp(), 0, 0) }
        layoutParams = lp
    }

    // ── 设备信息日志 ─────────────────────────────────────────

    private fun logDeviceInfo() {
        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "  Device UUID : ${tokenManager.deviceUuid}")
        Log.i(TAG, "  Device Role : ${tokenManager.deviceRole ?: "未设置"}")
        Log.i(TAG, "═══════════════════════════════════════════")
    }

    // ── 权限处理 ─────────────────────────────────────────────

    /**
     * 第一步：申请前台位置权限（Fine + Coarse 合并为一次弹框）。
     * 已授权时直接推进到后台位置权限检查。
     */
    private fun requestForegroundLocation() {
        val needed = FOREGROUND_LOCATION_PERMS.filter { !isGranted(it) }
        if (needed.isEmpty()) {
            Log.i(TAG, "前台位置权限已授予")
            maybeRequestBackgroundLocation()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_FOREGROUND_LOC)
        }
    }

    /**
     * 第二步：ACCESS_BACKGROUND_LOCATION 必须在前台权限授予后单独申请（Android 10+）。
     * 低于 API 29 的设备跳过此步骤。
     */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BACKGROUND_LOC
            )
        } else {
            Log.i(TAG, "后台位置权限已授予（或系统版本 < API 29）")
        }
    }

    /**
     * PACKAGE_USAGE_STATS 属于 AppOps 特殊权限，无法通过 requestPermissions 弹框申请。
     * 若未授权，跳转系统「有权查看使用情况的应用」设置页，引导用户手动开启。
     */
    private fun checkUsageStatsPermission() {
        if (UsageMonitor(this).hasUsageStatsPermission()) {
            Log.i(TAG, "PACKAGE_USAGE_STATS：已授权")
        } else {
            Log.w(TAG, "PACKAGE_USAGE_STATS 未授权 → 跳转系统设置，请手动开启本应用的使用情况访问权限")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND_LOC -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                Log.i(TAG, "前台位置权限：${if (allGranted) "已授予" else "被拒绝"}")
                if (allGranted) maybeRequestBackgroundLocation()
            }
            REQ_BACKGROUND_LOC -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "后台位置权限（ACCESS_BACKGROUND_LOCATION）：${if (granted) "已授予" else "被拒绝"}")
            }
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ── 测试函数 ─────────────────────────────────────────────

    /**
     * 扮演**被监护者**：
     *  1. 向服务器注册设备（角色 = ward）
     *  2. 请求生成6位配对码并打印
     *  3. 启动 [DataSyncWorker] 周期性后台上报
     *
     * 查看 Logcat TAG=MainActivity，找到带 ✅ 的行获取配对码。
     */
    fun testGenerateCode() = scope.launch {
        val uuid = tokenManager.deviceUuid
        Log.i(TAG, "▶ testGenerateCode  uuid=$uuid")
        runCatching {
            // 注册/更新角色为 ward
            ApiClient.api.registerDevice(RegisterRequest(uuid, DeviceRole.WARD.value))
            tokenManager.deviceRole = DeviceRole.WARD
            Log.i(TAG, "  设备已注册为 WARD")

            // 请求配对码
            val resp = ApiClient.api.generatePairing(GeneratePairingRequest(uuid))
            Log.i(TAG, "  ✅ 配对码：${resp.pairingCode}  (有效至 ${resp.expiresAt})")

            // 启动后台同步
            DataSyncWorker.schedule(this@MainActivity)
            Log.i(TAG, "  DataSyncWorker 已调度（每15分钟执行一次）")
        }.onFailure { e ->
            Log.e(TAG, "  ❌ testGenerateCode 失败：${e.message}")
        }
    }

    /**
     * 扮演**监护者**：
     *  1. 向服务器注册设备（角色 = guardian）
     *  2. 用 [code] 调用 `/pair/confirm` 完成双向绑定
     *  3. 打印绑定结果（guardian_id、ward_id）
     *
     * @param code 从被监护者设备 Logcat 复制的6位配对码
     */
    fun testConfirmCode(code: String) = scope.launch {
        val uuid = tokenManager.deviceUuid
        Log.i(TAG, "▶ testConfirmCode  uuid=$uuid  code=$code")
        runCatching {
            // 注册/更新角色为 guardian
            ApiClient.api.registerDevice(RegisterRequest(uuid, DeviceRole.GUARDIAN.value))
            tokenManager.deviceRole = DeviceRole.GUARDIAN
            Log.i(TAG, "  设备已注册为 GUARDIAN")

            // 确认配对
            val binding = ApiClient.api.confirmPairing(ConfirmPairingRequest(uuid, code))
            Log.i(TAG, "  ✅ 配对成功！")
            Log.i(TAG, "  Guardian ID : ${binding.guardianId}")
            Log.i(TAG, "  Ward ID     : ${binding.wardId}")
            Log.i(TAG, "  绑定时间    : ${binding.createdAt}")
        }.onFailure { e ->
            Log.e(TAG, "  ❌ testConfirmCode 失败：${e.message}")
        }
    }

    /**
     * 扮演**监护者**：
     *  调用 `/data/latest` 拉取被监护者的最新位置和手机最后使用时间，并打印。
     *
     * 前置条件：本机已完成配对（即先执行过 [testConfirmCode]）。
     */
    fun testFetchData() = scope.launch {
        val uuid = tokenManager.deviceUuid
        Log.i(TAG, "▶ testFetchData  guardianUuid=$uuid")
        runCatching {
            val data = ApiClient.api.getLatestData(uuid)
            Log.i(TAG, "  ✅ 被监护者最新数据：")
            Log.i(TAG, "  Ward UUID    : ${data.wardUuid}")
            Log.i(TAG, "  位置         : (${data.latitude}, ${data.longitude})")
            Log.i(TAG, "  服务端最后收到: ${data.lastSeen}")
            Log.i(TAG, "  手机最后使用  : ${data.lastUsedAt ?: "暂无记录"}")
        }.onFailure { e ->
            Log.e(TAG, "  ❌ testFetchData 失败：${e.message}")
        }
    }

    // ── 常量 ─────────────────────────────────────────────────

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_FOREGROUND_LOC = 1001
        private const val REQ_BACKGROUND_LOC = 1002

        private val FOREGROUND_LOCATION_PERMS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
