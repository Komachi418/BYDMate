package com.bydmate.app.n9

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
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
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClientImpl
import com.bydmate.app.helper.HelperBinderHolder
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Deliberately separate from the normal application graph: no tracker, automation,
 * restore manager, permission grants or vehicle-control UI is started here.
 * Every network/binder operation below follows a user button press.
 */
class N9DiagnosticsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyStore by lazy { AdbKeyStore(applicationContext) }
    // These two seams let the Activity tests exercise the real reader without a socket.
    internal var adbFactory: () -> AdbOnDeviceClient = { AdbOnDeviceClientImpl(applicationContext, keyStore) }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val adb by lazy { adbFactory() }
    private val helper by lazy { HelperClientImpl() }
    private val bootstrap by lazy { HelperBootstrap(adb, helper, applicationContext) }
    private val history by lazy { N9BatteryHistory(applicationContext) }
    private val actionButtons = mutableListOf<Button>()
    private val values = mutableMapOf<String, TextView>()
    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private lateinit var timeView: TextView
    private lateinit var historyView: TextView
    private lateinit var diagnosticStatus: TextView
    private var snapshots = emptyList<N9BatterySnapshot>()
    private var currentSnapshot: N9BatterySnapshot? = null
    private var currentSaved = true
    private var diagnosticText = ""
    private var pendingExportFile: String? = null
    private var exporting = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.N9_READ_ONLY) { "Diagnostics must use the isolated read-only variant" }
        diagnosticText = savedInstanceState?.getString(STATE_DIAGNOSTICS).orEmpty().takeLast(MAX_DIAGNOSTIC_CHARS)
        pendingExportFile = savedInstanceState?.getString(STATE_EXPORT)?.takeIf { EXPORT_FILE_REGEX.matches(it) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        body.addView(label("BYDMate N9 电池", 28f))
        body.addView(label("停车后点击读取。只读电池数据，最近 20 次记录保存在本机。", 16f))
        statusView = label("正在加载本地历史…", 18f).apply { tag = "batteryStatus" }
        body.addView(statusView)
        body.addView(metricRow(listOf("soc" to "电量 SOC", "soh" to "车机 SoH"), 38f))
        body.addView(metricRow(listOf("min" to "最低单体", "max" to "最高单体", "delta" to "单体压差"), 27f))
        body.addView(metricRow(listOf("total" to "累计电量"), 26f))
        timeView = label("读取时间：尚未读取", 16f).apply { tag = "batteryTime" }
        body.addView(timeView)
        body.addView(actionButton("读取电池并保存", "readBattery") { readAndSaveBattery() }.apply {
            textSize = 22f
            minHeight = dp(60)
        })
        body.addView(label("首次连接可能需要确认车机的 ADB 授权弹窗。显示“—”的项目表示本次没有有效读数。", 14f))
        body.addView(label("车机上报值，单次读数不代表实际容量或校准结论。", 14f))

        body.addView(actionButton("复制完整报告", "copyReport") {
            if (!canAct()) return@actionButton
            val report = completeReport()
            try {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("BYDMate N9 battery report", report))
                Toast.makeText(this, "已复制当前结果与全部历史", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "复制失败，请尝试导出 TXT。", Toast.LENGTH_LONG).show()
            }
        })
        body.addView(actionButton("导出完整报告 TXT", "exportReport") { exportReport() })
        body.addView(label("最近记录", 22f))
        historyView = label("尚无记录", 16f).apply {
            tag = "batteryHistory"
            setTextIsSelectable(true)
        }
        body.addView(historyView)

        val diagnosticArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(Button(this).apply {
            text = "展开可选诊断"
            tag = "expandDiagnostics"
            setOnClickListener {
                val expanded = diagnosticArea.visibility != View.VISIBLE
                diagnosticArea.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "收起可选诊断" else "展开可选诊断"
            }
        })
        diagnosticArea.addView(label("以下检查可选，不影响主页电池读取，也不会修改主页读数。", 15f))
        diagnosticStatus = label("可选诊断尚未执行。", 15f)
        diagnosticArea.addView(diagnosticStatus)
        diagnosticArea.addView(actionButton("观察 TLS ADB（约 8 秒）") {
            runDiagnostic("正在观察 TLS ADB…") { observeTls() }
        })
        diagnosticArea.addView(actionButton("Helper 电池读取对照") {
            runDiagnostic("正在进行 Helper 电池对照…") { probeHelper() }
        })
        reportView = label("", 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        diagnosticArea.addView(reportView)
        diagnosticArea.addView(label(NOTICE, 13f).apply {
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        })
        body.addView(diagnosticArea)
        setContentView(ScrollView(this).apply { addView(body) })
        loadHistory()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun actionButton(title: String, viewTag: String? = null, action: () -> Unit) = Button(this).apply {
        text = title
        tag = viewTag
        setOnClickListener { action() }
        actionButtons += this
    }

    private fun metricRow(items: List<Pair<String, String>>, valueSize: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { (key, title) ->
            val card = LinearLayout(this@N9DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(242, 246, 248))
                    cornerRadius = dp(10).toFloat()
                }
                addView(label(title, 15f))
                val value = label("—", valueSize).apply {
                    tag = key
                    typeface = Typeface.DEFAULT_BOLD
                }
                values[key] = value
                addView(value)
            }
            addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
    }

    private fun canAct(): Boolean = !busy && !exporting && pendingExportFile == null

    private fun updateControls() {
        actionButtons.forEach { it.isEnabled = canAct() }
    }

    private fun loadHistory() {
        busy = true
        updateControls()
        scope.launch {
            try {
                snapshots = withContext(ioDispatcher) { history.load() }
                currentSnapshot = snapshots.lastOrNull()
                renderSnapshot(currentSnapshot, historical = true)
                statusView.text = if (currentSnapshot == null) "尚未读取，点击下方按钮开始。"
                    else "已保存历史，尚未刷新" + if (currentSnapshot?.hasReadings == false) " · 最近一次失败" else ""
                renderHistory()
                renderDiagnostics()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusView.text = "本地历史读取失败，可以重新读取电池。"
                renderDiagnostics()
            } finally {
                busy = false
                updateControls()
            }
        }
    }

    private fun renderSnapshot(snapshot: N9BatterySnapshot?, historical: Boolean = false) {
        // Replace every value, including nulls. Missing values can never borrow an older row.
        values.getValue("soc").text = snapshot?.soc?.let { format(it.toDouble(), 1) + " %" } ?: "—"
        values.getValue("soh").text = snapshot?.soh?.let { "$it %" } ?: "—"
        values.getValue("min").text = snapshot?.minCellMv?.let { format(it / 1000.0, 3) + " V" } ?: "—"
        values.getValue("max").text = snapshot?.maxCellMv?.let { format(it / 1000.0, 3) + " V" } ?: "—"
        values.getValue("delta").text = snapshot?.deltaMv?.let { "$it mV" } ?: "—"
        values.getValue("total").text = snapshot?.totalElecConsumption?.let { format(it.toDouble(), 1) + " kWh" } ?: "—"
        timeView.text = snapshot?.let {
            (if (historical) "历史读取时间：" else "本次读取时间：") + displayTime(it.capturedAtMillis)
        } ?: "读取时间：尚未读取"
    }

    private fun renderHistory() {
        historyView.text = snapshots.asReversed().joinToString("\n\n") { item ->
            buildString {
                appendLine(displayTime(item.capturedAtMillis) + if (!item.hasReadings) " · 本次失败" else "")
                appendLine("SOC ${item.soc?.let { format(it.toDouble(), 1) + "%" } ?: "—"}  ·  " +
                    "SoH ${item.soh?.let { "$it%" } ?: "—"}  ·  " +
                    "累计 ${item.totalElecConsumption?.let { format(it.toDouble(), 1) + " kWh" } ?: "—"}")
                append("最低 ${item.minCellMv?.let { format(it / 1000.0, 3) + " V" } ?: "—"}  ·  " +
                    "最高 ${item.maxCellMv?.let { format(it / 1000.0, 3) + " V" } ?: "—"}  ·  " +
                    "压差 ${item.deltaMv?.let { "$it mV" } ?: "—"}")
            }
        }.ifBlank { "尚无记录" }
    }

    private fun readAndSaveBattery() {
        if (!canAct()) return
        busy = true
        updateControls()
        currentSnapshot = null
        renderSnapshot(null)
        statusView.text = "读取中…"
        timeView.text = "正在读取本次数据"
        scope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    val snapshot = try {
                        N9BatteryReader(adb).read()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        N9BatterySnapshot(System.currentTimeMillis(), null, null, null, null, null,
                            boundedN9Diagnostic("Battery read failed: ${failure.javaClass.simpleName}: ${failure.message}"))
                    }
                    val saved = runCatching { history.append(snapshot) }.getOrDefault(false)
                    // Save and re-read are synchronous in this IO block: a completed snapshot
                    // reaches disk even if the Activity is destroyed before the UI resumes.
                    Triple(snapshot, saved, if (saved) runCatching { history.load() }
                        .getOrDefault((snapshots + snapshot).takeLast(20)) else snapshots)
                }
                currentSnapshot = result.first
                currentSaved = result.second
                snapshots = result.third
                renderSnapshot(result.first)
                val complete = result.first.let {
                    it.soc != null && it.soh != null && it.totalElecConsumption != null &&
                        it.minCellMv != null && it.maxCellMv != null && it.deltaMv != null
                }
                statusView.text = when {
                    !result.first.hasReadings -> "本次失败，没有有效读数"
                    !complete -> "本次读取完成，部分数据不可用"
                    else -> "本次读取完成"
                } + if (result.second) " · 已保存" else " · 保存失败，请复制或导出报告"
                renderHistory()
                renderDiagnostics()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentSnapshot = null
                renderSnapshot(null)
                statusView.text = "本次失败，记录未完成，请重试。"
                appendDiagnostic("Battery operation failed: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                withContext(NonCancellable + ioDispatcher) { runCatching { adb.shutdown() } }
                busy = false
                updateControls()
            }
        }
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
        appendLine("主页通道=classic 5555 / autoservice GET；各次握手和原始 Parcel 见对应记录。")
        appendLine("Helper/TLS 仅手动可选诊断；没有对应记录即未执行。")
        appendLine("独立包：不操作官方 BYDMate 数据或 WRITE_SECURE_SETTINGS 授权。")
        appendLine("TLS 仅观察，不配对、不恢复端口、不改系统设置。")
        appendLine("数据为顺序读取的诊断值，不代表电池健康结论。")
        appendLine(NOTICE)
    }

    private fun runDiagnostic(progress: String, action: suspend () -> String) {
        if (!canAct()) return
        busy = true
        updateControls()
        diagnosticStatus.text = progress
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { action() }
                appendDiagnostic(result)
                diagnosticStatus.text = "可选检查完成，结果已加入完整报告。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appendDiagnostic("检查失败：${failure.javaClass.simpleName}: ${failure.message}")
                diagnosticStatus.text = "可选检查未完成，错误已记录。主页结果未变。"
            } finally {
                // No persistent ADB connection or background battery polling after a check.
                withContext(NonCancellable + ioDispatcher) { runCatching { adb.shutdown() } }
                busy = false
                updateControls()
            }
        }
    }

    private fun appendDiagnostic(section: String) {
        val addition = "\n--- ${Instant.now()} ---\n$section\n"
        val combined = diagnosticText + addition
        val marker = "[较早的可选诊断已省略；电池历史另行完整保留]\n"
        diagnosticText = if (combined.length <= MAX_DIAGNOSTIC_CHARS) combined
            else marker + combined.takeLast(MAX_DIAGNOSTIC_CHARS - marker.length)
        renderDiagnostics()
    }

    private fun renderDiagnostics() {
        reportView.text = buildString {
            append(identityReport())
            currentSnapshot?.let { appendLine(it.toBatteryReportText()) }
            append(diagnosticText)
        }
    }

    private fun completeReport(): String = buildString {
        append(identityReport())
        appendLine("[displayed battery result]")
        appendLine("screen state=${statusView.text}")
        appendLine("current saved=$currentSaved")
        appendLine(currentSnapshot?.toBatteryReportText() ?: "尚未读取")
        appendLine(n9BatteryHistoryText(snapshots))
        appendLine("[optional diagnostics; absent means NOT_RUN]")
        append(diagnosticText)
    }.let { full ->
        // History bounds each raw diagnostic to 16 KiB and keeps 20 rows, so the
        // ordinary full report fits. Keep an explicit last-resort bound as well.
        if (full.length <= MAX_REPORT_CHARS) full
        else full.take(MAX_REPORT_CHARS) + "\n[REPORT SIZE LIMIT: trailing text truncated]\n" + NOTICE
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
            appendLine("Helper battery comparison=NOT_RUN（Helper 未就绪或版本不匹配；主页仍可单独读取）")
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
        if (!canAct()) return
        val frozenReport = completeReport()
        exporting = true
        updateControls()
        scope.launch {
            var cacheFile: File? = null
            try {
                // Freeze a complete report before opening the picker. Save only the small
                // filename in instance state, never hundreds of KiB of raw Parcel history.
                cacheFile = withContext(ioDispatcher) {
                    File(cacheDir, "n9_battery_export_${UUID.randomUUID()}.txt").also {
                        it.writeText(frozenReport, Charsets.UTF_8)
                    }
                }
                pendingExportFile = cacheFile.name
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "bydmate_n9_${System.currentTimeMillis()}.txt")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, EXPORT_REQUEST)
            } catch (cancelled: CancellationException) {
                cacheFile?.delete()
                throw cancelled
            } catch (_: Exception) {
                cacheFile?.delete()
                pendingExportFile = null
                Toast.makeText(this@N9DiagnosticsActivity, "无法打开文件选择器，请使用复制完整报告。", Toast.LENGTH_LONG).show()
            } finally {
                exporting = false
                updateControls()
            }
        }
    }

    @Deprecated("Uses the platform document picker without loading the production application graph")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val fileName = pendingExportFile
        pendingExportFile = null
        val uri = data?.data
        if (fileName == null) return
        val frozenFile = File(cacheDir, fileName)
        if (resultCode != RESULT_OK || uri == null) {
            frozenFile.delete()
            updateControls()
            return
        }
        exporting = true
        updateControls()
        scope.launch {
            try {
                val saved = withContext(NonCancellable + ioDispatcher) {
                    runCatching {
                        frozenFile.inputStream().use { input ->
                            checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { output -> input.copyTo(output) }
                        }
                    }.also { frozenFile.delete() }
                }
                Toast.makeText(this@N9DiagnosticsActivity,
                    if (saved.isSuccess) "完整报告已导出" else "导出失败，请复制完整报告。", Toast.LENGTH_LONG).show()
            } finally {
                exporting = false
                updateControls()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DIAGNOSTICS, diagnosticText)
        outState.putString(STATE_EXPORT, pendingExportFile)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun displayTime(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    companion object {
        private const val EXPORT_REQUEST = 901
        private const val STATE_DIAGNOSTICS = "n9_diagnostics"
        private const val STATE_EXPORT = "n9_export"
        private const val MAX_DIAGNOSTIC_CHARS = 32_768
        private const val MAX_REPORT_CHARS = 512_000
        private val EXPORT_FILE_REGEX = Regex("n9_battery_export_[0-9a-f-]{36}\\.txt")
        private const val NOTICE = "PolyForm Noncommercial 1.0.0\n" +
            "https://polyformproject.org/licenses/noncommercial/1.0.0\n" +
            "Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)"
    }
}
