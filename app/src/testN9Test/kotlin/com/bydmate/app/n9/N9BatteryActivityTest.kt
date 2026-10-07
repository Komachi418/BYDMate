package com.bydmate.app.n9

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.nativestack.FidMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Real Activity + reader + history; all ADB IO is an in-memory fake. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class N9BatteryActivityTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var context: Context
    private var controller: ActivityController<N9DiagnosticsActivity>? = null
    private var factoryCalls = 0

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Application>()
        // A test owns this sandbox's app-private history only.
        listOf("n9-battery-history.json", "n9-battery-history.json.bak", "n9-battery-history.json.new")
            .forEach { File(context.filesDir, it).delete() }
    }

    @After fun finish() {
        controller?.pause()?.stop()?.destroy()
        scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun start(adb: FakeAdb = FakeAdb()): N9DiagnosticsActivity {
        val created = Robolectric.buildActivity(N9DiagnosticsActivity::class.java)
        controller = created
        val activity = created.get()
        activity.ioDispatcher = dispatcher
        activity.adbFactory = { factoryCalls++; adb }
        created.create().start().resume().visible()
        scheduler.advanceUntilIdle()
        return activity
    }

    private fun N9DiagnosticsActivity.text(tag: String): String =
        window.decorView.findViewWithTag<TextView>(tag).text.toString()

    private fun N9DiagnosticsActivity.button(tag: String): Button = window.decorView.findViewWithTag(tag)

    private fun seedHistory() {
        assertTrue(N9BatteryHistory(context).append(N9BatterySnapshot(
            1_700_000_000_000L, 99f, 100, 209.6f, 3323, 3330, "original history raw Parcel")))
    }

    @Test fun `cold launch loads locally without constructing ADB or generating keys`() {
        val adb = FakeAdb()
        val activity = start(adb)
        assertEquals(0, factoryCalls)
        assertEquals(0, adb.connects)
        assertEquals(0, adb.shutdowns)
        assertFalse(File(context.filesDir, "adb_keys").exists())
        assertEquals("—", activity.text("soc"))
        assertTrue(activity.text("batteryStatus").contains("尚未读取"))
        assertTrue(activity.button("readBattery").isEnabled)
    }

    @Test fun `history is labeled and failed refresh clears every previous value then saves failure`() {
        seedHistory()
        val adb = FakeAdb().apply { failConnect = true }
        val activity = start(adb)
        assertEquals("99.0 %", activity.text("soc"))
        assertEquals("已保存历史，尚未刷新", activity.text("batteryStatus"))
        assertTrue(activity.text("batteryTime").startsWith("历史读取时间"))
        assertEquals(0, factoryCalls)

        activity.button("readBattery").performClick()
        assertEquals("读取中…", activity.text("batteryStatus"))
        listOf("soc", "soh", "min", "max", "delta", "total").forEach {
            assertEquals("—", activity.text(it))
        }
        assertFalse(activity.button("copyReport").isEnabled)
        assertFalse(activity.button("exportReport").isEnabled)
        scheduler.advanceUntilIdle()

        listOf("soc", "soh", "min", "max", "delta", "total").forEach {
            assertEquals("—", activity.text(it))
        }
        assertTrue(activity.text("batteryStatus").contains("本次失败"))
        assertTrue(activity.text("batteryStatus").contains("已保存"))
        assertEquals(2, N9BatteryHistory(context).load().size)
        assertFalse(N9BatteryHistory(context).load().last().hasReadings)
        assertEquals(1, adb.connects)
        assertEquals(1, adb.shutdowns)
        assertTrue(adb.commands.isEmpty())

        activity.button("copyReport").performClick()
        val report = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip!!.getItemAt(0).text.toString()
        assertTrue(report.contains("original history raw Parcel"))
        assertTrue(report.contains("handshake=false"))
        assertTrue(report.contains("--- 2/2 ---"))
        assertTrue(report.contains("Required Notice: Copyright AndyShaman"))
    }

    @Test fun `partial refresh never fills missing fields or delta from historical values`() {
        seedHistory()
        val socFid = FidMap.byField.getValue("soc").fid
        val adb = FakeAdb().apply {
            response = { command ->
                if (command.endsWith("i32 $socFid")) "Result: Parcel(\t00000000 42c60000   '........')" else null
            }
        }
        val activity = start(adb)
        activity.button("readBattery").performClick()
        scheduler.advanceUntilIdle()
        assertEquals("99.0 %", activity.text("soc"))
        listOf("soh", "min", "max", "delta", "total").forEach { assertEquals("—", activity.text(it)) }
        assertTrue(activity.text("batteryStatus").contains("部分数据不可用"))
        assertEquals(5, adb.commands.size)
        assertTrue(adb.commands.all { it.matches(Regex("service call autoservice [57] i32 \\d+ i32 -?\\d+")) })
        assertEquals(1, adb.shutdowns)
        val last = N9BatteryHistory(context).load().last()
        assertEquals(99f, last.soc)
        assertNull(last.soh)
        assertNull(last.deltaMv)
    }

    @Test fun `closing during refresh disconnects and does not save an unfinished row`() {
        val adb = FakeAdb().apply { waitForConnect = CompletableDeferred() }
        val activity = start(adb)
        activity.button("readBattery").performClick()
        scheduler.runCurrent()
        assertEquals(1, adb.connects)
        assertFalse(activity.button("exportReport").isEnabled)
        controller!!.pause().stop().destroy()
        controller = null
        scheduler.advanceUntilIdle()
        assertEquals(1, adb.shutdowns)
        assertTrue(N9BatteryHistory(context).load().isEmpty())
    }

    private class FakeAdb : AdbOnDeviceClient {
        var connects = 0
        var shutdowns = 0
        var failConnect = false
        var waitForConnect: CompletableDeferred<Unit>? = null
        var response: (String) -> String? = { null }
        val commands = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> {
            connects++
            waitForConnect?.await()
            return if (failConnect) Result.failure(IOException("offline")) else Result.success(Unit)
        }
        override suspend fun isConnected() = !failConnect
        override fun lastConnectFailure() = if (failConnect) AdbConnectFailure.UNREACHABLE else null
        override suspend fun exec(cmd: String): String? {
            commands += cmd
            return response(cmd)
        }
        override suspend fun shutdown() { shutdowns++ }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun spawnHelper(token: String): Boolean = error("Unexpected Helper spawn")
        override suspend fun killHelper(): Boolean = error("Unexpected Helper kill")
        override suspend fun readHelperLog(): String? = error("Unexpected Helper log")
        override suspend fun helperHeartbeat(): Boolean = error("Unexpected Helper heartbeat")
    }
}
