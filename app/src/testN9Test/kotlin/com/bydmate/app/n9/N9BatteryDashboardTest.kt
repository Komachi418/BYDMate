package com.bydmate.app.n9

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class N9BatteryDashboardTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val states = MutableStateFlow(N9MonitorState())
    private var settings = N9MonitorSettings(false, null, null)
    private lateinit var context: Context
    private var controller: ActivityController<N9BatteryActivity>? = null
    private var enables = 0
    private var disables = 0
    private var refreshes = 0
    private var writes = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(N9RecordingStore.DATABASE_NAME)
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun start(): N9BatteryActivity {
        val built = Robolectric.buildActivity(N9BatteryActivity::class.java)
        controller = built
        val activity = built.get()
        activity.ioDispatcher = dispatcher
        activity.monitorStates = states
        activity.readSettings = { settings }
        activity.writeSettings = { _, tariff, capacity -> writes++; settings = settings.copy(tariff = tariff, nominalKwh = capacity) }
        activity.enableMonitor = { enables++; settings = settings.copy(enabled = true) }
        activity.disableMonitor = { disables++; settings = settings.copy(enabled = false) }
        activity.refreshMonitor = { refreshes++ }
        built.create().start().resume().visible()
        scheduler.advanceUntilIdle()
        return activity
    }

    private fun N9BatteryActivity.button(tag: String): Button = window.decorView.findViewWithTag(tag)
    private fun N9BatteryActivity.text(tag: String): String = window.decorView.findViewWithTag<TextView>(tag).text.toString()
    private fun N9BatteryActivity.input(tag: String): EditText = window.decorView.findViewWithTag(tag)
    private fun N9BatteryActivity.allText(): String {
        fun texts(view: View): List<String> = when (view) {
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            is TextView -> listOf(view.text.toString())
            else -> emptyList()
        }
        return texts(window.decorView).joinToString("\n")
    }
    private fun sample(time: Long, values: Map<String, Double>, error: String? = null) =
        N9Sample(1_800_000_000_000L + time, time + 1L, values, "Result: Parcel(\t00000000 42c60000)", error)

    @Test fun `cold launch and all tabs remain offline until explicit request`() {
        val activity = start()
        assertEquals("—", activity.text("value_soc"))
        assertTrue(activity.text("monitorStatus").contains("尚未读取"))
        (1..4).forEach { activity.button("tab$it").performClick() }
        assertEquals(0, enables)
        assertEquals(0, disables)
        assertEquals(0, refreshes)
        assertFalse(File(context.filesDir, "adb_keys").exists())
        assertTrue(activity.allText().contains("Required Notice: Copyright AndyShaman"))
        assertTrue(activity.input("capacityInput").text.isEmpty())
        activity.button("refresh").performClick()
        assertEquals(1, refreshes)
        assertEquals(0, enables)
    }

    @Test fun `only saved enable consent restores monitoring and stop is explicit`() {
        settings = settings.copy(enabled = true)
        val activity = start()
        assertEquals(1, enables)
        assertEquals(0, refreshes)
        activity.button("monitorToggle").performClick()
        assertEquals(1, disables)
        assertFalse(settings.enabled)
        activity.button("tab3").performClick()
        assertEquals(1, enables)
    }

    @Test fun `history is labeled and sampling failure never displays prior good values`() {
        N9RecordingStore(context).record(sample(0, mapOf("soc" to 99.0, "soh" to 100.0, "hvVoltage" to 600.0)))
        val activity = start()
        assertEquals("99.0 %", activity.text("value_soc"))
        assertTrue(activity.text("monitorStatus").contains("已保存历史，尚未刷新"))
        states.value = N9MonitorState(sampling = true)
        scheduler.advanceUntilIdle()
        assertEquals("—", activity.text("value_soc"))
        assertEquals("—", activity.text("value_hvVoltage"))
        activity.button("tab4").performClick()
        assertFalse(activity.button("export_txt").isEnabled)
        assertFalse(activity.button("export_csv").isEnabled)
        assertFalse(activity.button("export_copy").isEnabled)
        states.value = N9MonitorState(latest = sample(15_000, emptyMap(), "classic5555 offline"), status = "本次失败")
        scheduler.advanceUntilIdle()
        activity.button("tab0").performClick()
        assertEquals("—", activity.text("value_soc"))
        assertEquals("—", activity.text("value_soh"))
        assertEquals("—", activity.text("value_hvVoltage"))
        assertTrue(activity.text("monitorStatus").contains("失败"))
        states.value = N9MonitorState(latest = sample(30_000, mapOf("soc" to 98.0)))
        scheduler.advanceUntilIdle()
        assertEquals("98.0 %", activity.text("value_soc"))
        assertEquals("—", activity.text("value_soh"))
        assertEquals("—", activity.text("value_hvVoltage"))
    }

    @Test fun `settings reject invalid capacity and preserve unset values without default vehicle capacity`() {
        val activity = start()
        activity.button("tab4").performClick()
        activity.input("capacityInput").setText("0")
        activity.button("saveSettings").performClick()
        scheduler.advanceUntilIdle()
        assertEquals(0, writes)
        assertTrue(activity.text("settingsFeedback").contains("大于 0"))
        activity.input("capacityInput").setText("")
        activity.input("tariffInput").setText("0.85")
        activity.button("saveSettings").performClick()
        scheduler.advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(0.85, settings.tariff!!, 0.00001)
        assertNull(settings.nominalKwh)
        listOf("NaN", "Infinity", "-1", "abc").forEach { bad -> assertNotNull(parseN9Settings(bad, "").error) }
        listOf("NaN", "Infinity", "-1", "0", "abc").forEach { bad -> assertNotNull(parseN9Settings("", bad).error) }
        assertNull(parseN9Settings("0", " ").error)
        assertNull(parseN9Settings("", "").nominalKwh)
        assertEquals(0, enables)
        assertEquals(0, refreshes)
    }

    @Test fun `charge record opens details with measured fields coverage and insufficient capacity explanation`() {
        val store = N9RecordingStore(context)
        val charge = mapOf("bmsState" to 1.0, "chargerConnectState" to 1.0,
            "batteryPowerKw" to -20.0, "soc" to 50.0, "soh" to 100.0)
        store.record(sample(0, charge))
        store.record(sample(15_000, charge + ("soc" to 50.1)))
        val session = store.sessions("charge").single()
        val activity = start()
        activity.button("tab2").performClick()
        activity.button("session${session.id}").performClick()
        scheduler.advanceUntilIdle()
        assertTrue(activity.allText().contains("充电详情"))
        assertTrue(activity.allText().contains("采样覆盖率"))
        assertTrue(activity.allText().contains("本次条件不足"))
        assertTrue(activity.allText().contains("不是桩计费"))
        assertFalse(activity.allText().contains("折算容量 / 已设额定容量"))
        activity.button("backToList").performClick()
        assertNotNull(activity.button("session${session.id}"))
        assertEquals(0, refreshes)
        assertEquals(0, enables)
    }

    @Test fun `curve segments preserve real timestamps and break missing long gap nonfinite or reversed time`() {
        val values = listOf(
            sample(0, mapOf("soc" to 10.0)), sample(15_000, mapOf("soc" to 11.0)),
            sample(30_000, emptyMap()), sample(45_000, mapOf("soc" to 12.0)),
            sample(140_000, mapOf("soc" to 13.0)), sample(155_000, mapOf("soc" to Double.NaN)),
            sample(170_000, mapOf("soc" to 14.0)), sample(160_000, mapOf("soc" to 15.0)),
        )
        val lines = n9ChartSegments(values, "soc")
        assertEquals(listOf(2, 1, 1, 1, 1), lines.map { it.size })
        assertEquals(15_000L, lines.first()[1].timestamp - lines.first()[0].timestamp)
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0), lines.flatten().map { it.value })
        assertTrue(n9ChartSegments(values, "unsupported").isEmpty())
    }

    @Test fun `document chooser freezes one export and rejects another until its result`() {
        val activity = start()
        activity.button("tab4").performClick()
        activity.button("export_txt").performClick()
        scheduler.advanceUntilIdle()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertFalse(activity.button("export_csv").isEnabled)
        assertFalse(activity.button("export_copy").isEnabled)
        activity.button("export_csv").performClick()
        scheduler.advanceUntilIdle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        scheduler.advanceUntilIdle()
        assertTrue(activity.button("export_txt").isEnabled)
    }

    @Test fun `destroy during selected export finishes frozen copy before deleting source`() {
        val activity = start()
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var source: File? = null
        var copied: String? = null
        activity.writeFrozenExport = { file, _ ->
            source = file
            writing.complete(Unit)
            release.await()
            copied = file.readText()
        }
        activity.button("tab4").performClick()
        activity.button("export_txt").performClick()
        scheduler.advanceUntilIdle()
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://n9-test/report")))
        scheduler.runCurrent()
        assertTrue(writing.isCompleted)
        assertTrue(source!!.exists())
        controller!!.pause().stop().destroy()
        controller = null
        scheduler.runCurrent()
        assertTrue(source!!.exists())
        assertNull(copied)
        release.complete(Unit)
        scheduler.advanceUntilIdle()
        assertTrue(copied!!.contains("Required Notice: Copyright AndyShaman"))
        assertFalse(source!!.exists())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `render landscape overview and trip detail previews with explicit simulated data watermark`() {
        val store = N9RecordingStore(context)
        val values = mapOf("soc" to 72.5, "soh" to 100.0, "hvVoltage" to 610.0,
            "hvCurrent" to 18.0, "batteryPowerKw" to 10.98, "batteryRemainKwh" to 45.2,
            "voltage12v" to 13.4, "minCellVoltage" to 3.321, "maxCellVoltage" to 3.328,
            "cellDeltaMv" to 7.0, "minBatTemp" to 26.0, "maxBatTemp" to 29.0, "tempDeltaC" to 3.0,
            "speed" to 48.0, "mileage" to 12345.0, "frontPowerKw" to 7.5, "rearPowerKw" to 2.3, "compressorKw" to .8)
        repeat(3) { index -> store.record(sample(index * 15_000L, values + ("mileage" to 12345.0 + index * .2))) }
        store.finishOpen("模拟采样结束；此图不是 N9 真车结果")
        states.value = N9MonitorState(latest = store.latest(), status = "模拟数据 · 非真车结果 · 仅供界面预览")
        val session = store.sessions("trip").single()
        store.close()
        val activity = start()
        writePreview(activity, "overview.png")
        activity.button("tab3").performClick()
        activity.button("session${session.id}").performClick()
        scheduler.advanceUntilIdle()
        writePreview(activity, "trip-detail.png")
        assertEquals(0, enables)
        assertEquals(0, refreshes)
    }

    private fun writePreview(activity: N9BatteryActivity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1280, 720)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 209, 101); textSize = 21f }
        canvas.drawText("模拟数据 · 非真车结果 · UI 布局预览", 20f, 707f, paint)
        val destination = File("build/outputs/n9-preview", name)
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        assertTrue(destination.length() > 1024)
    }
}
