package com.bydmate.app.n9

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.AdbKeyStore
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AdbOnDeviceClientImpl
import com.bydmate.app.data.autoservice.AndroidAdbRestoreSystem
import com.bydmate.app.data.autoservice.AutoserviceClientImpl
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClientImpl
import com.bydmate.app.helper.HelperBinderHolder
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

/**
 * Deliberately separate from the normal application graph: no tracker, automation,
 * restore manager, permission grants or vehicle-control UI is started here.
 * Every network/binder operation below follows a user button press.
 */
class N9DiagnosticsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyStore by lazy { AdbKeyStore(applicationContext) }
    private val adb by lazy { AdbOnDeviceClientImpl(applicationContext, keyStore) }
    private val helper by lazy { HelperClientImpl() }
    private val bootstrap by lazy { HelperBootstrap(adb, helper, applicationContext) }
    private val diagnosticButtons = mutableListOf<Button>()
    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private var report = ""
    private var pendingExport: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.N9_READ_ONLY) { "Diagnostics must use the isolated read-only variant" }
        report = savedInstanceState?.getString(STATE_REPORT) ?: identityReport()
        pendingExport = savedInstanceState?.getString(STATE_EXPORT)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        body.addView(label("BYDMate N9 Test 2 · 只读电池对照", 25f))
        body.addView(label(
            "停车后操作。独立测试包，仅在点击时读取。首次连接可能需要确认车机的 ADB 授权弹窗。" +
                "第 3 项会通过 Helper 和直接读取两条路径对照电池数据，无需重启车机。", 16f))
        statusView = label("尚未连接；请选择一次检查。", 16f)
        body.addView(statusView)
        body.addView(diagnosticButton("1. 连接 classic 5555 并读取电池") {
            runDiagnostic("连接 ADB 并读取电池；如出现授权弹窗，请在车机确认。") { readBattery() }
        })
        body.addView(diagnosticButton("2. 观察 TLS ADB（约 8 秒）") {
            runDiagnostic("读取 TLS 设置、端口属性和本机 mDNS 广告…") { observeTls() }
        })
        body.addView(diagnosticButton("3. Helper 读取电池并对照") {
            runDiagnostic("连接独立 Helper，依次对照电池读数；无需重启车机…") { probeHelper() }
        })
        body.addView(Button(this).apply {
            text = "复制诊断报告"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("BYDMate N9 diagnostics", report))
                Toast.makeText(this@N9DiagnosticsActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        body.addView(Button(this).apply {
            text = "导出报告为 TXT"
            setOnClickListener { exportReport() }
        })
        reportView = label(report, 14f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        body.addView(reportView)
        body.addView(label(NOTICE, 13f).apply {
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun diagnosticButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
        diagnosticButtons += this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun identityReport(): String = buildString {
        appendLine("BYDMate N9 read-only diagnostic report")
        appendLine("time=${Instant.now()}")
        appendLine("package=$packageName version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("readOnly=${BuildConfig.N9_READ_ONLY}")
        appendLine("Android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
        appendLine("uid=${Process.myUid()} Android userId=${Process.myUid() / 100000}")
        appendLine("app process pid=${Process.myPid()}")
        appendLine("filesDir=$filesDir")
        appendLine("externalFilesDir=${getExternalFilesDir(null)}")
        appendLine("apk=$packageCodePath")
        appendLine("Helper process=${HelperBinderProtocol.PROCESS_NAME}")
        appendLine("Helper transport=NOT_RUN; classic5555=NOT_RUN; TLS=NOT_RUN")
        appendLine("autoservice probe/raw Parcel=NOT_RUN; SOC/SoH/min/max/cell delta=NOT_RUN")
        appendLine("Helper battery comparison=NOT_RUN（以下操作分段记录实际结果）")
        appendLine("独立包：不操作官方 BYDMate 数据或 WRITE_SECURE_SETTINGS 授权。")
        appendLine("TLS 仅观察，不配对、不恢复端口、不改系统设置。")
        appendLine("数据为顺序读取的诊断值，不代表电池健康结论。")
        appendLine(NOTICE)
    }

    private fun runDiagnostic(progress: String, action: suspend () -> String) {
        if (busy) return
        busy = true
        diagnosticButtons.forEach { it.isEnabled = false }
        statusView.text = progress
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                appendReport(result)
                statusView.text = "本次检查完成，可复制或导出报告。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appendReport("检查失败：${failure.javaClass.simpleName}: ${failure.message}")
                statusView.text = "检查未完成，错误已记录。"
            } finally {
                // No persistent ADB connection or background battery polling after a check.
                withContext(NonCancellable + Dispatchers.IO) { adb.shutdown() }
                busy = false
                diagnosticButtons.forEach { it.isEnabled = true }
            }
        }
    }

    private fun appendReport(section: String) {
        val addition = "\n--- ${Instant.now()} ---\n$section\n"
        if (report.length + addition.length > MAX_REPORT_CHARS) report = identityReport()
        report += addition
        reportView.text = report
    }

    private suspend fun readBattery(): String = buildString {
        appendLine("[classic 5555 / battery]")
        val connection = adb.connect()
        appendLine("classic5555 handshake=${connection.isSuccess} connected=${adb.isConnected()}")
        appendLine("classic5555 failure=${adb.lastConnectFailure() ?: "none"}")
        appendLine("检查结束后主动断开 ADB；以上为本次握手结果。")
        if (connection.isFailure) {
            appendLine("autoservice probe=NOT_RUN（classic ADB 未连接）")
            appendLine("SOC=不可用; SoH=不可用; min cell=不可用; max cell=不可用; cell delta=不可用")
            appendLine("连接错误=${connection.exceptionOrNull()?.message}")
            return@buildString
        }

        val transcript = StringBuilder()
        // Capture the exact response used by the production decoder, not a second probe
        // whose value/timing might differ. Delegation retains the original write barrier.
        val recordingAdb = object : AdbOnDeviceClient by adb {
            override suspend fun exec(cmd: String): String? = adb.exec(cmd).also { raw ->
                transcript.appendLine("\$ $cmd")
                transcript.appendLine(raw ?: "<transport returned null>")
            }
        }
        val autoservice = AutoserviceClientImpl(recordingAdb)
        suspend fun readInt(field: String): Int? = FidMap.byField.getValue(field).let {
            autoservice.getInt(it.device, it.fid)
        }
        suspend fun readFloat(field: String): Float? = FidMap.byField.getValue(field).let {
            autoservice.getFloat(it.device, it.fid)
        }
        val soh = readInt("soh")?.takeIf { it in 0..100 }
        val soc = readFloat("soc")?.takeIf { it in 0f..100f }
        val lifetime = readFloat("totalElecConsumption")?.takeIf { it >= 0f }
        fun cellVoltage(raw: Int?): Double? = raw?.let { it / 1000.0 }?.takeIf { it in 0.5..6.0 }
        val minCell = cellVoltage(readInt("minCellVoltage"))
        val maxCell = cellVoltage(readInt("maxCellVoltage"))
        val deltaMv = if (minCell != null && maxCell != null && maxCell >= minCell) {
            (maxCell - minCell) * 1000.0
        } else null
        appendLine("autoservice probe=${if (soh != null || soc != null || lifetime != null) "VALID_READING" else "NO_VALID_READING"}")
        appendLine("SoH=${soh?.let { "$it %" } ?: UNAVAILABLE}")
        appendLine("SOC=${soc?.let { "$it %" } ?: UNAVAILABLE}")
        appendLine("累计电量=${lifetime?.let { "$it kWh" } ?: UNAVAILABLE}")
        appendLine("min cell=${minCell?.let { format(it, 3) + " V" } ?: UNAVAILABLE}")
        appendLine("max cell=${maxCell?.let { format(it, 3) + " V" } ?: UNAVAILABLE}")
        appendLine("cell delta=${deltaMv?.let { format(it, 1) + " mV" } ?: UNAVAILABLE}")
        appendLine("使用仓库既有 FidMap；单体电压按 mV 解码，0.5–6 V 合理性校验。")
        appendLine("当前 N9 固件是否支持各 FID，以实际返回为准；不扫描未知接口。")
        appendLine("[autoservice raw Parcel — original whitespace preserved]")
        append(transcript)
        appendLine("classic5555 connected after reads=${adb.isConnected()}")
    }

    private suspend fun observeTls(): String = buildString {
        appendLine("[TLS ADB observation only]")
        val setting = runCatching { Settings.Global.getString(contentResolver, "adb_wifi_enabled") }
        appendLine("adb_wifi_enabled=${setting.getOrNull() ?: "unavailable/unset"}")
        setting.exceptionOrNull()?.let { appendLine("setting read failed=${it.javaClass.simpleName}") }
        val portProperty = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tls.port") as? String
        }
        appendLine("service.adb.tls.port=${portProperty.getOrNull()?.ifBlank { "unset" } ?: "unavailable"}")
        portProperty.exceptionOrNull()?.let { appendLine("property read failed=${it.javaClass.simpleName}") }
        // Use only this class's read-only, bounded, local-address-filtered discovery.
        // Never construct AdbRestoreManager or call restore/restart/selfGrant/write methods.
        val observer = AndroidAdbRestoreSystem(applicationContext, keyStore, adb, bootstrap, helper)
        val advertisedPort = observer.discoverTlsPort(8_000)
        appendLine("local _adb-tls-connect._tcp mDNS port=${advertisedPort ?: "not observed within 8 seconds"}")
        appendLine("TLS handshake=NOT_RUN; pairing=NOT_RUN; restore=NOT_RUN")
        appendLine("端口属性/广告不证明 TLS 已连接；未发现也不证明服务关闭。")
    }

    private suspend fun probeHelper(): String = buildString {
        appendLine("[isolated N9 Helper]")
        appendLine("Android userId=${Process.myUid() / 100000}; receiver package=$packageName")
        appendLine("expected broadcast action=${HelperBinderProtocol.ACTION_BINDER}")
        appendLine("process=${HelperBinderProtocol.PROCESS_NAME}; log=${HelperBinderProtocol.LOG_PATH}")
        val connection = adb.connect()
        appendLine("classic5555 handshake=${connection.isSuccess}; failure=${adb.lastConnectFailure() ?: "none"}")
        if (connection.isFailure) {
            appendLine("Helper bootstrap=NOT_RUN（classic ADB 未连接）")
            appendLine("Helper holder transport=${HelperBinderHolder.transport}")
            return@buildString
        }
        val ready = bootstrap.ensureRunning()
        val alive = helper.isAlive()
        val version = helper.daemonVersion()
        appendLine("bootstrap=$ready ping=$alive daemonVersion=${version ?: "unavailable"}")
        if (alive) appendLine("registerClient=${helper.registerClient() ?: "unavailable"}")
        val serviceLookup = runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, HelperBinderProtocol.SERVICE_NAME) as? android.os.IBinder
        }
        val serviceAlive = serviceLookup.getOrNull()?.isBinderAlive == true
        val broadcastAlive = HelperBinderHolder.binder?.isBinderAlive == true
        val transport = when {
            serviceAlive -> "servicemanager"
            broadcastAlive -> HelperBinderHolder.transport
            else -> "none"
        }
        appendLine("Helper transport=$transport; holder=${HelperBinderHolder.transport}")
        appendLine("ServiceManager lookup=${if (serviceLookup.isFailure) "unavailable (${serviceLookup.exceptionOrNull()?.javaClass?.simpleName})" else "binderAlive=$serviceAlive"}")
        appendLine("broadcast binderAlive=$broadcastAlive receivedAt=${HelperBinderHolder.receivedAt}")
        appendLine("broadcast lastReject=${HelperBinderHolder.lastReject ?: "none"}")
        bootstrap.lastSpawnFailure()?.let {
            appendLine("lastSpawnFailure=${it.reason}: ${redactToken(it.detail)}")
        }
        if (ready && alive && version == BuildConfig.VERSION_CODE.toLong()) {
            appendLine(N9BatteryComparison(adb, helper).read().toReportText())
        } else {
            appendLine("Helper battery comparison=NOT_RUN（Helper 未就绪或版本不匹配；可用第 1 项单独直接读取）")
        }
        appendLine("[isolated helper log]")
        appendLine(redactToken(adb.readHelperLog()?.takeLast(12_000) ?: "unavailable"))
        appendLine("Helper 电池对照仅使用既有 GET 接口；车辆写事务在此变体的 daemon 中被拒绝。")
    }

    private fun redactToken(text: String): String {
        val persisted = getSharedPreferences(HelperBinderHolder.PREFS_NAME, MODE_PRIVATE)
            .getString(HelperBinderHolder.KEY_SPAWN_TOKEN, null)
        return listOfNotNull(HelperBinderHolder.expectedToken, persisted)
            .filter { it.isNotEmpty() }.fold(text) { result, token -> result.replace(token, "<redacted>") }
    }

    private fun exportReport() {
        pendingExport = report
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "bydmate_n9_${System.currentTimeMillis()}.txt")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, EXPORT_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingExport = null
            Toast.makeText(this, "车机未提供文件选择器，请使用复制报告。", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker without loading the production application graph")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val snapshot = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || snapshot == null) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt"))
                        .bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                }
            }
            Toast.makeText(this@N9DiagnosticsActivity,
                if (saved.isSuccess) "报告已导出" else "导出失败，请复制报告。", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REPORT, report)
        outState.putString(STATE_EXPORT, pendingExport)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    companion object {
        private const val EXPORT_REQUEST = 901
        private const val STATE_REPORT = "n9_report"
        private const val STATE_EXPORT = "n9_export"
        private const val MAX_REPORT_CHARS = 80_000
        private const val UNAVAILABLE = "不可用（无有效读数/接口未确认，见 raw Parcel）"
        private const val NOTICE = "PolyForm Noncommercial 1.0.0\n" +
            "https://polyformproject.org/licenses/noncommercial/1.0.0\n" +
            "Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)"
    }
}
