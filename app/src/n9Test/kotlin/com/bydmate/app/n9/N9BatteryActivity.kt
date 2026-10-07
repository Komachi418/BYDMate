package com.bydmate.app.n9

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bydmate.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class N9SettingsInput(val tariff: Double?, val nominalKwh: Double?, val error: String? = null)

internal fun parseN9Settings(tariffText: String, capacityText: String): N9SettingsInput {
    fun number(text: String) = text.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val tariff = number(tariffText)
    val capacity = number(capacityText)
    if (tariffText.isNotBlank() && (tariff == null || !tariff.isFinite() || tariff !in 0.0..100.0))
        return N9SettingsInput(null, null, "电价请填 0–100 的有限数字，或留空。")
    if (capacityText.isNotBlank() && (capacity == null || !capacity.isFinite() || capacity <= 0 || capacity > 500))
        return N9SettingsInput(null, null, "额定容量请填大于 0、不超过 500 的有限数字，或留空。")
    return N9SettingsInput(tariff, capacity)
}

/** Independent battery-only UI. It never loads the production Hilt/control graph. */
class N9BatteryActivity : Activity() {
    private val activityJob = SupervisorJob()
    private val scope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var monitorStates: StateFlow<N9MonitorState> = N9Monitor.state
    internal var enableMonitor: (Context) -> Unit = N9Monitor::enable
    internal var disableMonitor: (Context) -> Unit = N9Monitor::disable
    internal var refreshMonitor: (Context) -> Unit = N9Monitor::refresh
    internal var readSettings: (Context) -> N9MonitorSettings = N9Monitor::settings
    internal var writeSettings: (Context, Double?, Double?) -> Unit = N9Monitor::saveSettings
    internal var writeFrozenExport: suspend (File, Uri) -> Unit = { source, uri ->
        val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
        output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
    }
    private val recordingStore = lazy { N9RecordingStore(applicationContext) }
    private val store by recordingStore
    private var collection: Job? = null
    private var monitor = N9MonitorState()
    private var settings = N9MonitorSettings(false, null, null)
    private var localLatest: N9Sample? = null
    private var recent = emptyList<N9Sample>()
    private var sessions = emptyList<N9Session>()
    private var detailPoints = emptyList<N9Sample>()
    private var page = 0
    private var detailId: Long? = null
    private var chartKey = "batteryPowerKw"
    private var loaded = false
    private var exportBusy = false
    private var pendingExport: String? = null
    private var settingsDraft: Pair<String, String>? = null
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var headerStatus: TextView
    private lateinit var monitorButton: Button
    private lateinit var refreshButton: Button
    private val tabs = mutableListOf<Button>()
    private val exportButtons = mutableListOf<Button>()
    private var tariffInput: EditText? = null
    private var capacityInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.N9_READ_ONLY)
        settings = readSettings(this)
        page = (savedInstanceState?.getInt("page") ?: 0).coerceIn(0, 4)
        detailId = savedInstanceState?.getLong("detailId", -1L)?.takeIf { it >= 0 }
        chartKey = savedInstanceState?.getString("chartKey") ?: "batteryPowerKw"
        pendingExport = savedInstanceState?.getString("pendingExport")?.takeIf { EXPORT_NAME.matches(it) }
        if (savedInstanceState?.containsKey("tariffDraft") == true) settingsDraft =
            savedInstanceState.getString("tariffDraft").orEmpty() to savedInstanceState.getString("capacityDraft").orEmpty()
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY
        val root = column().apply { setBackgroundColor(NAVY); setPadding(dp(14), dp(10), dp(14), dp(6)) }
        val header = row()
        header.addView(label("BYDMate N9 电池", 25f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        monitorButton = button("开启自动记录", "monitorToggle") { toggleMonitor() }
        refreshButton = button("刷新", "refresh") { safely { refreshMonitor(this) } }
        header.addView(monitorButton)
        header.addView(refreshButton)
        root.addView(header)
        headerStatus = label("正在加载本地记录…", 14f).apply { tag = "monitorStatus"; setPadding(dp(4), dp(3), 0, dp(10)) }
        root.addView(headerStatus)
        scroll = ScrollView(this).apply { isFillViewport = true }
        content = column().apply { setPadding(0, 0, 0, dp(12)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = row()
        listOf("总览", "电芯与健康", "充电", "行程能耗", "记录 / 设置").forEachIndexed { index, title ->
            val tab = button(title, "tab$index") { selectPage(index) }.apply { textSize = 15f; minWidth = 0 }
            tabs += tab
            navigation.addView(tab, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(navigation)
        setContentView(root)
        render()
        // Reading settings/history does not create an ADB client. Restore only explicit prior consent.
        if (settings.enabled) safely { enableMonitor(this) }
    }

    override fun onStart() {
        super.onStart()
        collection = scope.launch {
            monitorStates.collectLatest { next ->
                monitor = next
                settings = readSettings(this@N9BatteryActivity)
                if (loaded) { updateHeader(); if (page != 4) render() }
                if (!next.sampling || !loaded) reloadLocal()
            }
        }
    }

    override fun onStop() {
        collection?.cancel()
        collection = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        if (recordingStore.isInitialized()) CoroutineScope(ioDispatcher).launch {
            activityJob.join()
            store.close()
        }
        // Closing the UI does not cancel a monitor the user explicitly enabled.
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberSettingsDraft()
        outState.putInt("page", page)
        outState.putLong("detailId", detailId ?: -1L)
        outState.putString("chartKey", chartKey)
        outState.putString("pendingExport", pendingExport)
        settingsDraft?.let { outState.putString("tariffDraft", it.first); outState.putString("capacityDraft", it.second) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Native Activity back handling is retained for the isolated variant")
    override fun onBackPressed() {
        if (detailId != null) { detailId = null; render() } else super.onBackPressed()
    }

    private suspend fun reloadLocal() {
        try {
            val selected = detailId
            val data = withContext(ioDispatcher) {
                LocalData(store.latest(), store.recentSamples(120), store.sessions(), selected?.let { store.points(it) }.orEmpty())
            }
            val firstLoad = !loaded
            localLatest = data.latest
            recent = data.samples
            sessions = data.sessions
            if (selected == detailId) detailPoints = data.points
            loaded = true
            updateHeader()
            if (page != 4 || firstLoad) { rememberSettingsDraft(); render() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loaded = true; headerStatus.text = "读取本地记录失败：${failure.javaClass.simpleName}" }
    }

    private fun current(): N9Sample? = monitor.latest ?: localLatest
    private fun currentValues(): Map<String, Double> = if (monitor.sampling) emptyMap() else current()?.values.orEmpty()

    private fun updateHeader() {
        monitorButton.text = if (settings.enabled) "停止自动记录" else "开启自动记录"
        monitorButton.isEnabled = !exportBusy
        refreshButton.isEnabled = !monitor.sampling && !exportBusy
        exportButtons.forEach { it.isEnabled = !monitor.sampling && !exportBusy && pendingExport == null }
        val sample = current()
        val freshness = when {
            monitor.sampling -> "读取中 · 本次数值暂不可用"
            sample == null -> "尚未读取"
            sample.values.isEmpty() || sample.connectionError != null -> "本次读取失败 / 不完整 · ${date(sample.timestamp)}"
            monitor.latest == null -> "已保存历史，尚未刷新 · ${date(sample.timestamp)}"
            else -> "最近读取 ${date(sample.timestamp)}"
        }
        headerStatus.text = "$freshness\n${monitor.status}"
    }

    private fun toggleMonitor() = safely {
        if (settings.enabled) disableMonitor(this) else enableMonitor(this)
        settings = readSettings(this)
        updateHeader()
    }

    private fun rememberSettingsDraft() {
        if (page == 4 && tariffInput != null && capacityInput != null)
            settingsDraft = tariffInput!!.text.toString() to capacityInput!!.text.toString()
    }

    private fun selectPage(index: Int) {
        rememberSettingsDraft()
        page = index
        detailId = null
        chartKey = when (page) { 1 -> "soh"; 2 -> "batteryPowerKw"; 3 -> "batteryPowerKw"; else -> "batteryPowerKw" }
        render()
        scroll.scrollTo(0, 0)
    }

    private fun render() {
        val oldScroll = scroll.scrollY
        content.removeAllViews()
        exportButtons.clear()
        tariffInput = null
        capacityInput = null
        tabs.forEachIndexed { index, button -> button.background = rounded(if (index == page) GREEN_DARK else CARD, if (index == page) GREEN else BORDER) }
        if (detailId != null) renderDetail() else when (page) {
            0 -> renderOverview()
            1 -> renderHealth()
            2 -> renderSessions("charge")
            3 -> renderSessions("trip")
            else -> renderSettings()
        }
        updateHeader()
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun renderOverview() {
        val values = currentValues()
        val socCard = card("电池电量")
        socCard.addView(label(format(values["soc"], 1, "%"), 58f, GREEN, true).apply { tag = "value_soc"; gravity = Gravity.CENTER })
        socCard.addView(label("SOC · 车机上报", 16f).apply { gravity = Gravity.CENTER })
        socCard.addView(label(if (monitor.sampling) "读取中…" else current()?.connectionError?.let { "连接异常；本次缺失值不沿用历史" } ?: "只读监测 · 所有记录保存在本机", 14f).apply { gravity = Gravity.CENTER })
        val pack = card("最近电池读数")
        addFields(pack, values, listOf("batteryPowerKw", "hvVoltage", "hvCurrent", "batteryRemainKwh", "voltage12v", "soh"))
        columns(socCard, pack)
        val cell = card("电芯与温度")
        addFields(cell, values, listOf("minCellVoltage", "maxCellVoltage", "cellDeltaMv", "minBatTemp", "maxBatTemp", "tempDeltaC"))
        val journey = card("正在记录")
        val active = sessions.filter { it.status == "active" }
        if (active.isEmpty()) journey.addView(label("等待识别充电或行程", 20f, WHITE))
        else active.forEach { session ->
            journey.addView(button("${kindName(session.kind)} · ${date(session.startedAt)}\n${sessionSummary(session)}", "active${session.id}") { showDetail(session) })
        }
        journey.addView(label("开启一次后自动识别充电与行程。仅在车机通电、ADB 可达时记录；休眠或断网会留下空缺。", 15f))
        columns(cell, journey)
        addChart(content, recent, listOf("batteryPowerKw", "soc", "maxBatTemp", "voltage12v"), "最近 120 次记录")
        note("— 表示未读到或接口尚未支持。扩展接口的数值、单位与方向仍需 N9 真机核对。电池功率由总压 × 电流估算，正值放电，负值入电。")
    }

    private fun renderHealth() {
        val values = currentValues()
        val cells = card("电芯一致性")
        addFields(cells, values, listOf("minCellVoltage", "maxCellVoltage", "cellDeltaMv", "minBatTemp", "maxBatTemp", "tempDeltaC"))
        cells.addView(label("当前接口提供最高 / 最低单体；尚无逐节电芯列表。", 14f))
        val health = card("BMS 与累计数据")
        addFields(health, values, listOf("soh", "batteryRemainKwh", "totalElecConsumption", "mileage", "insulationKohm", "voltage12v"))
        columns(cells, health)
        val limits = card("BMS 功率限制")
        addFields(limits, values, listOf("bmsMaxChargeKw", "bmsMaxDischargeAllowKw", "bmsMaxDischargeKw"))
        content.addView(limits)
        val chargeHistory = sessions.filter { it.kind == "charge" }.asReversed().map { session ->
            N9Sample(session.endedAt ?: session.startedAt, 0L, buildMap {
                session.metrics["endSoh"]?.let { put("soh", it) }
                session.metrics["maxCellDeltaMv"]?.let { put("cellDeltaMv", it) }
                session.metrics["minTempC"]?.let { put("minBatTemp", it) }
                session.metrics["maxTempC"]?.let { put("maxBatTemp", it) }
                session.metrics["endSoc"]?.let { put("soc", it) }
            }, "")
        }
        addChart(content, chargeHistory, listOf("soh", "cellDeltaMv", "maxBatTemp", "minBatTemp", "soc"), "跨次充电趋势 · ${chargeHistory.size} 个观察区间", independentPoints = true)
        note("趋势每点代表一次充电的末次 SoH / SOC、区间最大压差或温度边界。请结合下方 SOC 与温度范围比较，不同条件的读数不直接等价。")
        val snapshots = card("充电健康记录")
        val charges = sessions.filter { it.kind == "charge" }.take(12)
        if (charges.isEmpty()) snapshots.addView(label("识别到充电后，会保留起始 SoH、SOC，并随采样记录电芯与温度。", 16f))
        charges.forEach { session ->
            snapshots.addView(button("${date(session.startedAt)} · ${statusName(session.status)}\nSoH ${format(session.metrics["startSoh"], 0, "%")} → ${format(session.metrics["endSoh"], 0, "%")}  ·  最大压差 ${format(session.metrics["maxCellDeltaMv"], 1, "mV")}\nSOC ${format(session.metrics["startSoc"], 1, "%")} → ${format(session.metrics["endSoc"], 1, "%")}  ·  温度 ${format(session.metrics["minTempC"], 0, "℃")} – ${format(session.metrics["maxTempC"], 0, "℃")}", "health${session.id}") { showDetail(session) })
        }
        content.addView(snapshots)
        addChart(content, recent, listOf("cellDeltaMv", "maxBatTemp", "minBatTemp", "soc", "voltage12v"), "最近 120 次采样")
        note("SoH 是车机上报值，单次读数不代表实际容量或校准结论。充电区间容量折算仅作趋势参考。扩展接口需 N9 真机核对；支持情况见记录 / 设置。")
    }

    private fun renderSessions(kind: String) {
        val records = sessions.filter { it.kind == kind }
        val summary = card(if (kind == "charge") "充电记录" else "行程能耗")
        summary.addView(label("已保存 ${records.size} 次${kindName(kind)} · 点击记录查看详情", 19f, WHITE))
        summary.addView(label(if (kind == "charge") "识别充电开始时保留健康数据，过程中记录功率、电量、电芯和温度。" else "每次行程分别记录公里数、电池进出电量、前后电机及压缩机能耗。", 15f))
        val complete = records.filter { it.status == "complete" }
        fun sum(key: String): Double? = complete.mapNotNull { it.metrics[key] }.takeIf { it.isNotEmpty() }?.sum()
        val totals = if (kind == "charge") "已结束观察区间入电 ${format(sum("batteryInKwh"), 2, "kWh")}（采样估算）" else
            "已结束观察区间 ${format(sum("distanceKm"), 1, "km")} · 净电量 ${format(sum("netKwh"), 2, "kWh")}"
        summary.addView(label(totals, 17f, GREEN))
        content.addView(summary)
        if (records.isEmpty()) note("尚无${kindName(kind)}记录。点击上方“开启自动记录”，识别条件满足后会自动开始；缺失状态不会凭空生成行程或充电。")
        records.take(80).forEach { session ->
            val card = card("${date(session.startedAt)} · ${statusName(session.status)}")
            card.addView(label(sessionSummary(session), 21f, WHITE, true))
            card.addView(label("${date(session.startedAt)} → ${session.endedAt?.let(::date) ?: "进行中"}", 14f))
            card.addView(button("查看${kindName(kind)}详情与曲线", "session${session.id}") { showDetail(session) })
            content.addView(card)
        }
        if (records.size > 80) note("此页显示最近 80 次；导出包含全部保留记录。")
        note("记录仅代表已观察区间，可能不覆盖整次${kindName(kind)}。扩展接口及状态判断仍需 N9 真机验证。")
        if (kind == "trip") note("前后电机与压缩机是独立接口的区间估算，不能相加当作完整车辆能耗。入电来源未区分，压缩机不代表全部空调负载。")
    }

    private fun showDetail(session: N9Session) {
        detailId = session.id
        detailPoints = emptyList()
        chartKey = "batteryPowerKw"
        render()
        scroll.scrollTo(0, 0)
        scope.launch { reloadLocal() }
    }

    private fun renderDetail() {
        content.addView(button("‹ 返回${if (page == 0 || page == 1) "上一页" else kindName(if (page == 2) "charge" else "trip") + "列表"}", "backToList") { detailId = null; render() })
        val session = sessions.firstOrNull { it.id == detailId }
        if (session == null) { note("正在加载记录，或该记录已不在本机保存范围内。"); return }
        val metrics = session.metrics
        val headline = card("${kindName(session.kind)}详情 · ${statusName(session.status)}")
        headline.addView(label("${date(session.startedAt)} → ${session.endedAt?.let(::date) ?: "进行中"}", 17f, WHITE))
        headline.addView(label("SOC ${format(metrics["startSoc"], 1, "%")} → ${format(metrics["endSoc"], 1, "%")}  ·  时长 ${duration(metrics["durationSeconds"])}", 21f, GREEN, true))
        if (session.note.isNotBlank()) headline.addView(label(session.note, 15f))
        content.addView(headline)
        val energy = card(if (session.kind == "charge") "充电区间" else "行程与电池")
        metric(energy, "记录公里数", metrics["distanceKm"], 2, "km")
        metric(energy, "电池放出电量 · 估算", metrics["batteryOutKwh"], 3, "kWh")
        metric(energy, "电池流入电量 · 估算", metrics["batteryInKwh"], 3, "kWh")
        metric(energy, "净电量 · 放出减流入", metrics["netKwh"], 3, "kWh")
        if (session.kind == "trip") {
            val distance = metrics["distanceKm"]
            val net = metrics["netKwh"]
            val covered = metrics["coverageBattery"] == 1.0 && metrics["coverageDistance"] == 1.0
            metric(energy, "每百公里净电量 · 区间参考", if (covered && distance != null && distance > 0 && net != null) net / distance * 100 else null, 2, "kWh/100km")
        } else {
            metric(energy, "峰值入电功率 · 估算", metrics["peakChargeKw"], 2, "kW")
            val capacity = metrics["capacityEstimateKwh"]
            metric(energy, "充电区间折算容量 · 趋势估算", capacity, 2, "kWh")
            if (capacity == null) energy.addView(label("本次条件不足：需要至少 20% 的 SOC 窗口与连续、有效的采样。", 14f))
            if (capacity != null && settings.nominalKwh != null)
                metric(energy, "折算容量 / 已设额定容量 · 非 SoH", capacity / settings.nominalKwh!! * 100, 1, "%")
            val cost = settings.tariff?.let { tariff -> metrics["batteryInKwh"]?.times(tariff) }
            metric(energy, "按当前电价估算费用", cost, 2, "元")
            energy.addView(label("费用 = 有效采样入电 × 当前设置电价；不含桩端损耗，不是桩计费。容量折算受 SOC 区间、采样空缺和充电效率影响。", 14f))
        }
        val detail = card(if (session.kind == "charge") "充电健康数据" else "分项能耗 · 区间估算")
        if (session.kind == "trip") {
            metric(detail, "前电机耗电", metrics["frontOutKwh"], 3, "kWh")
            metric(detail, "后电机耗电", metrics["rearOutKwh"], 3, "kWh")
            metric(detail, "压缩机耗电", metrics["compressorKwh"], 3, "kWh")
            detail.addView(label("分项来自各接口，不构成完整能耗拆分，不能相加当作总电量。压缩机不含全部空调负载。", 14f))
        } else {
            metric(detail, "起始 BMS SoH", metrics["startSoh"], 0, "%")
            metric(detail, "结束 BMS SoH", metrics["endSoh"], 0, "%")
        }
        metric(detail, "最大电芯压差", metrics["maxCellDeltaMv"], 1, "mV")
        metric(detail, "最低 / 最高温度", metrics["minTempC"], 1, "℃ / ${format(metrics["maxTempC"], 1, "℃")}")
        columns(energy, detail)
        val coverage = card("采样覆盖率")
        listOf("电池" to "coverageBattery", "公里数" to "coverageDistance", "前电机" to "coverageFront", "后电机" to "coverageRear", "压缩机" to "coverageCompressor").forEach { (title, key) ->
            metric(coverage, title, metrics[key]?.times(100), 1, "%")
        }
        coverage.addView(label("仅对连续且有效的采样区间累计；超过 90 秒、时钟变化或字段缺失会断开，未覆盖部分不补算。电池流入可能来自多种来源，不标作纯再生。", 14f))
        content.addView(coverage)
        addChart(content, detailPoints, if (session.kind == "charge") listOf("batteryPowerKw", "soc", "soh", "cellDeltaMv", "maxBatTemp", "minBatTemp") else listOf("batteryPowerKw", "frontPowerKw", "rearPowerKw", "compressorKw", "speed", "soc"), "${kindName(session.kind)}曲线 · ${detailPoints.size} 次采样")
    }

    private fun renderSettings() {
        val recording = card("自动记录")
        recording.addView(label(if (settings.enabled) "已开启 · 下次打开或车机启动后继续监测" else "未开启 · 只有点击刷新时读取一次", 19f, GREEN))
        recording.addView(label("开启一次后，自动识别充电开始与行程，记录电池数据。仅在车机通电、ADB 可达时工作；不唤醒车机、不补造休眠或断网数据。停止后保留已有记录。", 16f))
        content.addView(recording)
        val configuration = card("费用与容量")
        configuration.addView(label("电价（元 / kWh，可留空）", 16f))
        tariffInput = numberInput("tariffInput", settingsDraft?.first ?: settings.tariff?.toString().orEmpty())
        configuration.addView(tariffInput)
        configuration.addView(label("额定电池容量（kWh，可留空）", 16f))
        capacityInput = numberInput("capacityInput", settingsDraft?.second ?: settings.nominalKwh?.toString().orEmpty())
        configuration.addView(capacityInput)
        configuration.addView(label("没有预设车型容量。此项仅保存参考规格，BMS 读数和区间积分不使用它补值。", 14f))
        val feedback = label("", 15f, GREEN).apply { tag = "settingsFeedback" }
        configuration.addView(button("保存设置", "saveSettings") {
            val input = parseN9Settings(tariffInput!!.text.toString(), capacityInput!!.text.toString())
            if (input.error != null) { feedback.text = input.error; return@button }
            scope.launch {
                try {
                    withContext(ioDispatcher) { writeSettings(this@N9BatteryActivity, input.tariff, input.nominalKwh) }
                    settings = readSettings(this@N9BatteryActivity)
                    settingsDraft = null
                    feedback.text = "已保存"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback.text = "保存失败：${failure.javaClass.simpleName}" }
            }
        })
        configuration.addView(feedback)
        content.addView(configuration)
        val export = card("记录与导出")
        export.addView(label("本机保留 ${sessions.size} 条会话；最近曲线显示 ${recent.size} 次采样。导出包含全部保留会话、采样和最近原始诊断。", 16f))
        listOf("导出 CSV" to "csv", "导出 TXT" to "txt", "复制 TXT 报告" to "copy").forEach { (title, type) ->
            val action = button(title, "export_$type") { prepareExport(type) }
            exportButtons += action
            export.addView(action)
        }
        export.addView(label("读取结束后可导出；选择保存位置期间使用已冻结的完整报告，不会混入半条记录。", 14f))
        export.addView(button("原版只读诊断与 505 历史", "legacyDiagnostics") { startActivity(Intent(this, N9DiagnosticsActivity::class.java)) })
        content.addView(export)
        val fields = card("接口与诊断")
        fields.addView(label("Android ${Build.VERSION.RELEASE} · userId ${Process.myUid() / 100000}\n${BuildConfig.APPLICATION_ID}\n版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n读取通道：经典 ADB 5555；Helper / TLS 对照见原版诊断", 14f))
        val values = currentValues()
        addFields(fields, values, N9Fields.definitions.map { it.key })
        fields.addView(label("原始 Parcel / 最近诊断", 17f, WHITE, true))
        fields.addView(label(current()?.raw?.take(16_384) ?: "尚无诊断", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        content.addView(fields)
        val license = card("许可与署名")
        license.addView(label("PolyForm Noncommercial 1.0.0\nhttps://polyformproject.org/licenses/noncommercial/1.0.0\nRequired Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)", 14f).apply {
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(GREEN)
        })
        content.addView(license)
    }

    private fun prepareExport(type: String) {
        if (monitor.sampling || exportBusy || pendingExport != null) return
        exportBusy = true
        updateHeader()
        scope.launch {
            try {
                val report = withContext(ioDispatcher) { if (type == "csv") store.exportCsv() else store.exportText() }
                if (type == "copy") {
                    if (report.length > 400_000) { toast("报告较大，请使用 TXT 导出。") }
                    else {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("N9 电池报告", report))
                        toast("完整报告已复制")
                    }
                } else {
                    val filename = "n9-export-${UUID.randomUUID()}.$type"
                    withContext(ioDispatcher) { File(cacheDir, filename).writeText(report, Charsets.UTF_8) }
                    pendingExport = filename
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        this.type = if (type == "csv") "text/csv" else "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "BYDMate-N9-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.$type")
                    }, EXPORT_REQUEST)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                pendingExport?.let { File(cacheDir, it).delete() }
                pendingExport = null
                toast("导出未完成：${failure.javaClass.simpleName}")
            }
            finally { exportBusy = false; updateHeader() }
        }
    }

    @Deprecated("Activity result API keeps this variant independent of the production graph")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val filename = pendingExport ?: return
        pendingExport = null
        val frozen = File(cacheDir, filename)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { frozen.delete(); updateHeader(); return }
        exportBusy = true
        updateHeader()
        scope.launch {
            try {
                // Once the user chose a destination, finish this frozen copy before
                // deleting the source, even if rotation destroys the Activity.
                withContext(NonCancellable + ioDispatcher) {
                    writeFrozenExport(frozen, uri)
                }
                toast("报告已导出")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toast("保存失败：${failure.javaClass.simpleName}") }
            finally { frozen.delete(); exportBusy = false; updateHeader() }
        }
    }

    private fun addChart(parent: LinearLayout, points: List<N9Sample>, keys: List<String>, title: String, independentPoints: Boolean = false) {
        val card = card(title)
        val selected = chartKey.takeIf { it in keys } ?: keys.first()
        val field = N9Fields.definitions.first { it.key == selected }
        val controls = android.widget.HorizontalScrollView(this)
        val buttons = row()
        keys.forEach { key ->
            val definition = N9Fields.definitions.first { it.key == key }
            buttons.addView(button(definition.label.substringBefore("（"), "chart_$key") { chartKey = key; render() }.apply {
                textSize = 14f
                background = rounded(if (key == selected) GREEN_DARK else CARD, if (key == selected) GREEN else BORDER)
            })
        }
        controls.addView(buttons)
        card.addView(controls)
        card.addView(label("${field.label} (${field.unit})${if (!field.verifiedOnN9) " · N9 接口待核对" else ""}", 14f))
        card.addView(N9BatteryChartView(this, points, field, independentPoints), LinearLayout.LayoutParams(-1, dp(210)))
        card.addView(label(if (independentPoints) "横轴为每次充电观察区间的结束时间；各次独立绘点，不连接。" else "横轴为真实采样时间；字段缺失或间隔超过 90 秒处断线。", 13f))
        parent.addView(card)
    }

    private fun addFields(parent: LinearLayout, values: Map<String, Double>, keys: List<String>) {
        keys.forEach { key ->
            val field = N9Fields.definitions.firstOrNull { it.key == key } ?: return@forEach
            val value = values[key]
            val detail = if (value == null) "未读到 / 暂不支持" else if (page == 4 && !field.verifiedOnN9) "N9 接口待核对" else ""
            val row = row().apply { setPadding(0, dp(6), 0, dp(6)) }
            val title = column().apply {
                addView(label(field.label.substringBefore("（"), 16f))
                if (detail.isNotEmpty()) addView(label(detail, 11f))
            }
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(format(value, field.decimals, field.unit), 22f, WHITE, true).apply { tag = "value_$key"; gravity = Gravity.END })
            parent.addView(row)
        }
    }

    private fun metric(parent: LinearLayout, title: String, value: Double?, decimals: Int, unit: String) {
        val row = row().apply { setPadding(0, dp(6), 0, dp(6)) }
        row.addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(format(value, decimals, unit), 21f, WHITE, true))
        parent.addView(row)
    }

    private fun columns(left: LinearLayout, right: LinearLayout) {
        if (resources.configuration.screenWidthDp >= 760) {
            val row = row().apply { gravity = Gravity.TOP }
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), dp(10)) })
            row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 0, dp(10)) })
            content.addView(row)
        } else { content.addView(left); content.addView(right) }
    }

    private fun card(title: String) = column().apply {
        background = rounded(CARD, BORDER)
        setPadding(dp(16), dp(12), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        addView(label(title, 18f, GREEN, true).apply { setPadding(0, 0, 0, dp(8)) })
    }
    private fun note(text: String) { content.addView(label(text, 15f).apply { setPadding(dp(5), dp(6), dp(5), dp(12)) }) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(text: String, size: Float, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(text: String, viewTag: String, action: () -> Unit) = Button(this).apply {
        this.text = text; tag = viewTag; textSize = 16f; isAllCaps = false; setTextColor(WHITE)
        minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(CARD, BORDER)
        setOnClickListener { action() }
    }
    private fun numberInput(viewTag: String, value: String) = EditText(this).apply {
        tag = viewTag; setText(value); textSize = 20f; setTextColor(WHITE); setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setSingleLine(true); hint = "未设置"; minHeight = dp(52)
    }
    private fun rounded(color: Int, border: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
    }
    private fun safely(block: () -> Unit) { try { block() } catch (failure: Exception) { toast("操作未完成：${failure.javaClass.simpleName}") } }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun date(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
    private fun format(value: Double?, decimals: Int, unit: String): String = value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.${decimals}f", it) + if (unit.isBlank()) "" else " $unit"
    } ?: "—"
    private fun duration(seconds: Double?): String = seconds?.takeIf { it.isFinite() && it >= 0 }?.toLong()?.let { "${it / 3600} 时 ${it / 60 % 60} 分 ${it % 60} 秒" } ?: "—"
    private fun kindName(kind: String) = if (kind == "charge") "充电" else "行程"
    private fun statusName(status: String) = when (status) { "active" -> "进行中"; "complete" -> "已完成"; "interrupted" -> "已中断"; else -> status }
    private fun sessionSummary(session: N9Session): String = if (session.kind == "charge")
        "SOC ${format(session.metrics["startSoc"], 1, "%")} → ${format(session.metrics["endSoc"], 1, "%")}  ·  入电 ${format(session.metrics["batteryInKwh"], 2, "kWh")}" else
        "${format(session.metrics["distanceKm"], 1, "km")}  ·  净电量 ${format(session.metrics["netKwh"], 2, "kWh")}"

    private data class LocalData(val latest: N9Sample?, val samples: List<N9Sample>, val sessions: List<N9Session>, val points: List<N9Sample>)
    private companion object {
        val NAVY = Color.rgb(10, 23, 42)
        val CARD = Color.rgb(17, 35, 57)
        val BORDER = Color.rgb(40, 63, 85)
        val GREEN = Color.rgb(79, 221, 151)
        val GREEN_DARK = Color.rgb(25, 74, 66)
        val WHITE = Color.rgb(235, 245, 251)
        val MUTED = Color.rgb(159, 181, 204)
        const val EXPORT_REQUEST = 2607
        val EXPORT_NAME = Regex("n9-export-[a-f0-9-]{36}\\.(csv|txt)")
    }
}
