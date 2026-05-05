package com.guardian.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardian.app.network.ApiClient
import com.guardian.app.network.ConfirmPairingRequest
import com.guardian.app.network.RegisterRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * 应用主 Activity。
 *
 * 实现三屏状态机（无 XML 布局，动态切换）：
 *  - [Screen.ROLE_SELECT]  首次启动，选择角色并完成设备注册
 *  - [Screen.WARD]         被监护者视图：生成/展示配对码、同步状态、权限状态
 *  - [Screen.GUARDIAN]     监护者视图：输入配对码、查看被监护者数据
 */
class MainActivity : Activity() {

    private enum class Screen { ROLE_SELECT, WARD, GUARDIAN }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val tokenManager by lazy { TokenManager(this) }
    private val handler = Handler(Looper.getMainLooper())

    // ── 跨屏共享根布局 ────────────────────────────────────────
    private lateinit var rootScroll: ScrollView
    private lateinit var rootLinear: LinearLayout

    // ── Ward 屏动态组件 ───────────────────────────────────────
    private lateinit var wardPairingStatus: TextView
    private lateinit var wardCodeCard: LinearLayout    // 配对码大字 + 倒计时（可见/隐藏）
    private lateinit var wardCodeText: TextView
    private lateinit var wardCodeExpiry: TextView
    private lateinit var wardGenBtn: Button
    private lateinit var wardPermLocation: TextView
    private lateinit var wardPermUsage: TextView

    private var codeExpiresAt: Long = 0L
    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remaining = ((codeExpiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            wardCodeExpiry.text = getString(R.string.ward_code_expires, remaining)
            if (remaining > 0) handler.postDelayed(this, 1000)
            else wardCodeCard.visibility = View.GONE
        }
    }

    // ── Guardian 屏动态组件 ───────────────────────────────────
    private lateinit var guardianPairingStatus: TextView
    private lateinit var guardianInputArea: LinearLayout   // 未配对时展示
    private lateinit var guardianCodeInput: EditText
    private lateinit var guardianDataCard: LinearLayout    // 已配对时展示
    private lateinit var guardianWardUuid: TextView
    private lateinit var guardianLocation: TextView
    private lateinit var guardianLastUsed: TextView
    private lateinit var guardianLastSeen: TextView

    // ── 生命周期 ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootLinear = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }
        rootScroll = ScrollView(this).apply { addView(rootLinear) }
        setContentView(rootScroll)

        // 根据已保存角色决定展示哪个屏
        when (tokenManager.deviceRole) {
            DeviceRole.WARD     -> showScreen(Screen.WARD)
            DeviceRole.GUARDIAN -> showScreen(Screen.GUARDIAN)
            null                -> showScreen(Screen.ROLE_SELECT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(countdownRunnable)
        scope.cancel()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新权限状态显示
        if (tokenManager.deviceRole == DeviceRole.WARD && ::wardPermLocation.isInitialized) {
            refreshPermissionStatus()
        }
    }

    // ── 屏幕切换 ─────────────────────────────────────────────

    private fun showScreen(screen: Screen) {
        rootLinear.removeAllViews()
        when (screen) {
            Screen.ROLE_SELECT -> buildRoleSelectScreen()
            Screen.WARD        -> buildWardScreen()
            Screen.GUARDIAN    -> buildGuardianScreen()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ROLE SELECT 屏
    // ═══════════════════════════════════════════════════════════

    private fun buildRoleSelectScreen() {
        rootLinear.addView(textView(getString(R.string.role_select_title), 26f, bold = true,
            gravity = Gravity.CENTER_HORIZONTAL))
        rootLinear.addView(spacer(dp(8)))
        rootLinear.addView(textView(getString(R.string.role_select_subtitle), 15f,
            color = 0xFF888888.toInt(), gravity = Gravity.CENTER_HORIZONTAL))
        rootLinear.addView(spacer(dp(48)))

        rootLinear.addView(bigButton(getString(R.string.role_guardian), 0xFF1976D2.toInt()) {
            registerAs(DeviceRole.GUARDIAN)
        })
        rootLinear.addView(spacer(dp(16)))
        rootLinear.addView(bigButton(getString(R.string.role_ward), 0xFF388E3C.toInt()) {
            registerAs(DeviceRole.WARD)
        })
    }

    private fun registerAs(role: DeviceRole) {
        val uuid = tokenManager.deviceUuid
        scope.launch {
            val loadingToast = Toast.makeText(this@MainActivity,
                getString(R.string.loading), Toast.LENGTH_SHORT)
            loadingToast.show()
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.api.registerDevice(RegisterRequest(uuid, role.value))
                }
                tokenManager.authToken = resp.token
                tokenManager.deviceRole = role
                loadingToast.cancel()

                if (role == DeviceRole.WARD) {
                    DataSyncWorker.schedule(this@MainActivity)
                    showScreen(Screen.WARD)
                    requestForegroundLocation()
                } else {
                    GuardianPollWorker.schedule(this@MainActivity)
                    maybeRequestNotificationPermission()
                    showScreen(Screen.GUARDIAN)
                }
            } catch (e: Exception) {
                loadingToast.cancel()
                Toast.makeText(this@MainActivity,
                    getString(R.string.error_network), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // WARD 屏
    // ═══════════════════════════════════════════════════════════

    private fun buildWardScreen() {
        // 顶部身份栏
        rootLinear.addView(identityHeader(
            getString(R.string.ward_title),
            tokenManager.deviceUuid.take(8)
        ))
        rootLinear.addView(divider())

        // 配对状态
        wardPairingStatus = textView(getString(R.string.ward_pairing_status_unpaired), 15f,
            color = 0xFFE53935.toInt())
        rootLinear.addView(sectionLabel("配对状态"))
        rootLinear.addView(wardPairingStatus)

        // 配对码卡片（默认隐藏）
        wardCodeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wardCodeText = textView("------", 48f, bold = true, gravity = Gravity.CENTER_HORIZONTAL,
            color = 0xFF1976D2.toInt())
        wardCodeExpiry = textView("", 13f, color = 0xFF888888.toInt(),
            gravity = Gravity.CENTER_HORIZONTAL)
        wardCodeCard.addView(textView(getString(R.string.ward_code_hint), 13f,
            color = 0xFF888888.toInt(), gravity = Gravity.CENTER_HORIZONTAL))
        wardCodeCard.addView(wardCodeText)
        wardCodeCard.addView(wardCodeExpiry)
        rootLinear.addView(wardCodeCard)
        rootLinear.addView(spacer(dp(8)))

        // 生成配对码按钮
        wardGenBtn = button(getString(R.string.ward_btn_generate_code)) { generatePairingCode() }
        rootLinear.addView(wardGenBtn)
        rootLinear.addView(divider())

        // 权限状态
        rootLinear.addView(sectionLabel("权限状态"))
        wardPermLocation = clickableText("") { requestForegroundLocation() }
        wardPermUsage = clickableText("") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        rootLinear.addView(wardPermLocation)
        rootLinear.addView(wardPermUsage)

        // 初始化状态
        refreshPermissionStatus()
        refreshWardPairingStatus()
    }

    private fun generatePairingCode() {
        wardGenBtn.isEnabled = false
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ApiClient.api.generatePairing() }
                wardCodeText.text = resp.pairingCode
                codeExpiresAt = System.currentTimeMillis() + 5 * 60 * 1000L
                wardCodeCard.visibility = View.VISIBLE
                handler.removeCallbacks(countdownRunnable)
                handler.post(countdownRunnable)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.error_network), Toast.LENGTH_LONG).show()
            } finally {
                wardGenBtn.isEnabled = true
            }
        }
    }

    private fun refreshWardPairingStatus() {
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) { ApiClient.api.getPairingStatus() }
                if (status.paired) {
                    wardPairingStatus.text = getString(R.string.ward_pairing_status_paired)
                    wardPairingStatus.setTextColor(0xFF388E3C.toInt())
                    wardGenBtn.visibility = View.GONE
                    wardCodeCard.visibility = View.GONE
                } else {
                    wardPairingStatus.text = getString(R.string.ward_pairing_status_unpaired)
                    wardPairingStatus.setTextColor(0xFFE53935.toInt())
                    wardGenBtn.visibility = View.VISIBLE
                }
            } catch (_: Exception) { /* 网络失败时保持当前状态 */ }
        }
    }

    private fun refreshPermissionStatus() {
        val locOk = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        wardPermLocation.text = if (locOk) getString(R.string.ward_perm_location_ok)
        else getString(R.string.ward_perm_location_missing)
        wardPermLocation.setTextColor(if (locOk) 0xFF388E3C.toInt() else 0xFFE53935.toInt())

        val usageOk = UsageMonitor(this).hasUsageStatsPermission()
        wardPermUsage.text = if (usageOk) getString(R.string.ward_perm_usage_ok)
        else getString(R.string.ward_perm_usage_missing)
        wardPermUsage.setTextColor(if (usageOk) 0xFF388E3C.toInt() else 0xFFE53935.toInt())
    }

    // ═══════════════════════════════════════════════════════════
    // GUARDIAN 屏
    // ═══════════════════════════════════════════════════════════

    private fun buildGuardianScreen() {
        // 顶部身份栏
        rootLinear.addView(identityHeader(
            getString(R.string.guardian_title),
            tokenManager.deviceUuid.take(8)
        ))
        rootLinear.addView(divider())

        // 配对状态
        guardianPairingStatus = textView(getString(R.string.ward_pairing_status_unpaired), 15f,
            color = 0xFFE53935.toInt())
        rootLinear.addView(sectionLabel("配对状态"))
        rootLinear.addView(guardianPairingStatus)

        // 未配对：输入配对码区域
        guardianInputArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guardianCodeInput = EditText(this).apply {
            hint = getString(R.string.guardian_code_hint)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        guardianInputArea.addView(guardianCodeInput)
        guardianInputArea.addView(spacer(dp(8)))
        guardianInputArea.addView(button(getString(R.string.guardian_btn_confirm)) {
            val code = guardianCodeInput.text.toString().trim()
            if (code.length == 6) confirmPairing(code)
            else Toast.makeText(this, "请输入6位配对码", Toast.LENGTH_SHORT).show()
        })
        rootLinear.addView(guardianInputArea)

        // 已配对：被监护者数据卡片
        guardianDataCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        guardianWardUuid  = textView("", 14f, color = 0xFF555555.toInt())
        guardianLocation  = textView("", 15f)
        guardianLastUsed  = textView("", 15f)
        guardianLastSeen  = textView("", 13f, color = 0xFF888888.toInt())
        guardianDataCard.addView(guardianWardUuid)
        guardianDataCard.addView(spacer(dp(4)))
        guardianDataCard.addView(guardianLocation)
        guardianDataCard.addView(guardianLastUsed)
        guardianDataCard.addView(spacer(dp(4)))
        guardianDataCard.addView(guardianLastSeen)
        guardianDataCard.addView(spacer(dp(12)))
        guardianDataCard.addView(button(getString(R.string.guardian_btn_refresh)) { fetchLatestData() })
        rootLinear.addView(guardianDataCard)

        // 初始查询配对状态
        refreshGuardianPairingStatus()
    }

    private fun refreshGuardianPairingStatus() {
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) { ApiClient.api.getPairingStatus() }
                if (status.paired) {
                    guardianPairingStatus.text = getString(R.string.ward_pairing_status_paired)
                    guardianPairingStatus.setTextColor(0xFF388E3C.toInt())
                    guardianInputArea.visibility = View.GONE
                    guardianDataCard.visibility = View.VISIBLE
                    fetchLatestData()
                }
            } catch (_: Exception) { /* 保持未配对状态 */ }
        }
    }

    private fun confirmPairing(code: String) {
        scope.launch {
            val loadingToast = Toast.makeText(this@MainActivity,
                getString(R.string.loading), Toast.LENGTH_SHORT)
            loadingToast.show()
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.api.confirmPairing(ConfirmPairingRequest(code))
                }
                loadingToast.cancel()
                guardianPairingStatus.text = getString(R.string.ward_pairing_status_paired)
                guardianPairingStatus.setTextColor(0xFF388E3C.toInt())
                guardianInputArea.visibility = View.GONE
                guardianDataCard.visibility = View.VISIBLE
                fetchLatestData()
            } catch (e: Exception) {
                loadingToast.cancel()
                val msg = if (e.message?.contains("422") == true || e.message?.contains("invalid") == true)
                    "配对码无效或已过期" else getString(R.string.error_network)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchLatestData() {
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { ApiClient.api.getLatestData() }
                guardianWardUuid.text = "被监护者：${data.wardUuid.take(8)}…"
                guardianLocation.text = "位置：(${String.format("%.4f", data.latitude)}, " +
                        "${String.format("%.4f", data.longitude)})"
                guardianLastUsed.text = "最后使用手机：${
                    data.lastUsedAt?.let { relativeTime(parseIso(it)) } ?: "暂无记录"
                }"
                guardianLastSeen.text = "最后同步：${relativeTime(parseIso(data.lastSeen))}"
                guardianDataCard.visibility = View.VISIBLE
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.error_network), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 权限处理 ─────────────────────────────────────────────

    private fun requestForegroundLocation() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { !isGranted(it) }
        if (needed.isEmpty()) maybeRequestBackgroundLocation()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_FOREGROUND_LOC)
    }

    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BACKGROUND_LOC
            )
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND_LOC -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                    maybeRequestBackgroundLocation()
                if (::wardPermLocation.isInitialized) refreshPermissionStatus()
            }
            REQ_BACKGROUND_LOC -> {
                if (::wardPermLocation.isInitialized) refreshPermissionStatus()
            }
        }
    }

    private fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ── 时间工具 ─────────────────────────────────────────────

    private fun parseIso(iso: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(iso)?.time ?: System.currentTimeMillis()
    } catch (_: Exception) { System.currentTimeMillis() }

    private fun relativeTime(epochMs: Long): String {
        val diffMin = abs(System.currentTimeMillis() - epochMs) / 60_000
        return when {
            diffMin < 1    -> getString(R.string.time_just_now)
            diffMin < 60   -> getString(R.string.time_minutes_ago, diffMin)
            diffMin < 1440 -> getString(R.string.time_hours_ago, diffMin / 60)
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))
        }
    }

    // ── 可复用 View 工厂 ─────────────────────────────────────

    private fun identityHeader(role: String, shortUuid: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textView(role, 22f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(textView(shortUuid, 12f, color = 0xFF888888.toInt()))
        }

    private fun sectionLabel(text: String): TextView = textView(text, 12f,
        color = 0xFF888888.toInt()).apply { setPadding(0, dp(16), 0, dp(4)) }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .also { it.setMargins(0, dp(16), 0, dp(8)) }
        setBackgroundColor(0xFFDDDDDD.toInt())
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun textView(
        text: String, sizeSp: Float, bold: Boolean = false,
        color: Int = 0xFF212121.toInt(), gravity: Int = Gravity.NO_GRAVITY
    ) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        this.gravity = gravity
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun bigButton(text: String, bgColor: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(bgColor)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).also { it.setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun clickableText(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // ── 常量 ─────────────────────────────────────────────────

    companion object {
        private const val REQ_FOREGROUND_LOC = 1001
        private const val REQ_BACKGROUND_LOC = 1002
        private const val REQ_NOTIFICATION   = 1003
    }
}
