package com.bydmate.app.n9

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.nativestack.FidMap
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class N9MonitorServiceTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val originalAdbFactory = N9Monitor.adbFactory
    private val originalStoreFactory = N9Monitor.storeFactory
    private val originalIo = N9Monitor.ioDispatcher
    private lateinit var app: Application
    private lateinit var adb: FakeAdb
    private lateinit var store: N9RecordingStore
    private var controller: ServiceController<N9MonitorService>? = null
    private var factoryCalls = 0
    private val saved = mutableListOf<N9Sample>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("n9_battery_monitor", 0).edit().clear().commit()
        N9Monitor.update(N9MonitorState())
        N9Monitor.serviceActive = false
        Dispatchers.setMain(dispatcher)
        N9Monitor.ioDispatcher = dispatcher
        adb = FakeAdb()
        store = mockk(relaxed = true)
        every { store.record(any()) } answers { saved += firstArg<N9Sample>(); Unit }
        N9Monitor.adbFactory = { factoryCalls++; adb }
        N9Monitor.storeFactory = { store }
    }

    @After fun teardown() {
        adb.blockNext?.complete(Unit)
        adb.activeGate?.complete(Unit)
        controller?.destroy()
        scheduler.runCurrent()
        N9Monitor.adbFactory = originalAdbFactory
        N9Monitor.storeFactory = originalStoreFactory
        N9Monitor.ioDispatcher = originalIo
        N9Monitor.serviceActive = false
        N9Monitor.update(N9MonitorState())
        Dispatchers.resetMain()
    }

    private fun create(): N9MonitorService {
        controller = Robolectric.buildService(N9MonitorService::class.java).create()
        return controller!!.get()
    }
    private fun N9MonitorService.command(action: String?, id: Int) =
        onStartCommand(action?.let { Intent(app, N9MonitorService::class.java).setAction(it) }, 0, id)

    private fun destroy() {
        controller!!.destroy()
        controller = null
        scheduler.runCurrent()
    }

    @Test fun `foreground is registered before construction and single refresh saves once without enabling`() {
        val service = create()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(0, factoryCalls)
        verify(exactly = 0) { store.finishOpen(any()) }
        service.command(N9MonitorService.ACTION_REFRESH, 1)
        assertEquals(0, factoryCalls) // Still no IO work on the caller.
        scheduler.runCurrent()
        assertEquals(1, saved.size)
        assertEquals(98.0, saved.single().values.getValue("soc"), 0.0)
        assertFalse(N9Monitor.settings(app).enabled)
        assertEquals("本次刷新已保存", N9Monitor.state.value.status)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertEquals(1, adb.shutdowns)
        destroy()
        assertEquals("本次刷新已保存", N9Monitor.state.value.status)
        verify(exactly = 1) { store.close() }
    }

    @Test fun `stop cancels a blocked read waits for IO and discards earlier queued refreshes`() {
        N9Monitor.setEnabled(app, true)
        val gate = CompletableDeferred<Unit>()
        adb.blockNext = gate
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        assertTrue(N9Monitor.state.value.sampling)
        assertEquals(1, adb.activeReads)
        service.command(N9MonitorService.ACTION_REFRESH, 2)
        service.command(N9MonitorService.ACTION_REFRESH, 3)
        service.command(N9MonitorService.ACTION_STOP, 4)
        scheduler.runCurrent()
        assertEquals(0, adb.shutdowns)
        assertTrue(saved.isEmpty())
        gate.complete(Unit)
        scheduler.runCurrent()
        assertTrue(saved.isEmpty())
        assertEquals(1, adb.commands.size)
        assertEquals(1, adb.shutdowns)
        assertEquals(listOf(0), adb.readsAtShutdown)
        assertFalse(N9Monitor.settings(app).enabled)
        assertEquals(N9Monitor.STOPPED_STATUS, N9Monitor.state.value.status)
        scheduler.advanceTimeBy(45_000)
        scheduler.runCurrent()
        assertEquals(1, adb.commands.size)
        destroy()
        assertEquals(N9Monitor.STOPPED_STATUS, N9Monitor.state.value.status)
    }

    @Test fun `database record failure stops foreground and never publishes unsaved values or retries silently`() {
        every { store.record(any()) } throws IOException("disk full")
        N9Monitor.setEnabled(app, true)
        N9Monitor.update(N9MonitorState(latest = N9Sample(1, 1, mapOf("soc" to 12.0), "old")))
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        val state = N9Monitor.state.value
        assertFalse(state.running)
        assertFalse(state.sampling)
        assertTrue(state.latest!!.values.isEmpty())
        assertTrue(state.status.contains("保存失败"))
        assertFalse(state.status.contains("已保存"))
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(saved.isEmpty())
        assertEquals(1, adb.shutdowns)
        val count = adb.commands.size
        scheduler.advanceTimeBy(45_000)
        scheduler.runCurrent()
        assertEquals(count, adb.commands.size)
        destroy()
        assertEquals(state, N9Monitor.state.value)
        verify { store.finishOpen("读取或保存失败，连续记录中断") }
    }

    @Test fun `database initialization failure stops without constructing ADB and keeps error on destruction`() {
        every { store.finishOpen(any()) } throws IOException("database unavailable")
        val service = create()
        service.command(N9MonitorService.ACTION_REFRESH, 1)
        scheduler.runCurrent()
        val state = N9Monitor.state.value
        assertEquals(0, factoryCalls)
        assertTrue(state.latest!!.values.isEmpty())
        assertNotNull(state.latest.connectionError)
        assertTrue(shadowOf(service).isForegroundStopped)
        verify(exactly = 0) { store.record(any()) }
        destroy()
        assertEquals(state, N9Monitor.state.value)
    }

    @Test fun `connection loss saves a fresh empty sample and reconnects during enabled monitoring`() {
        N9Monitor.setEnabled(app, true)
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        assertTrue(N9Monitor.state.value.latest!!.hasReadings)
        adb.connected = false
        adb.connectionFailure = true
        scheduler.advanceTimeBy(15_000)
        scheduler.runCurrent()
        val failed = N9Monitor.state.value.latest!!
        assertTrue(failed.values.isEmpty())
        assertNotNull(failed.connectionError)
        assertEquals(failed, saved.last())
        assertTrue(N9Monitor.state.value.running)
        assertEquals(1, adb.shutdowns)
        adb.connectionFailure = false
        scheduler.advanceTimeBy(15_000)
        scheduler.runCurrent()
        assertEquals(3, saved.size)
        assertTrue(N9Monitor.state.value.latest!!.hasReadings)
        assertEquals(2, factoryCalls)
        assertEquals(2, adb.connects)
        service.command(N9MonitorService.ACTION_STOP, 2)
        scheduler.runCurrent()
    }

    @Test fun `newer refresh keeps foreground until its serialized read completes`() {
        val first = CompletableDeferred<Unit>()
        adb.blockNext = first
        val service = create()
        service.command(N9MonitorService.ACTION_REFRESH, 1)
        scheduler.runCurrent()
        service.command(N9MonitorService.ACTION_REFRESH, 2)
        val second = CompletableDeferred<Unit>()
        adb.blockNext = second
        adb.blockAtCommand = 31
        first.complete(Unit)
        scheduler.runCurrent()
        assertEquals(1, saved.size)
        assertEquals(1, adb.activeReads)
        assertFalse(shadowOf(service).isForegroundStopped)
        second.complete(Unit)
        scheduler.runCurrent()
        assertEquals(2, saved.size)
        assertEquals(1, adb.maxActiveReads)
        assertEquals(2, adb.shutdowns)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertEquals("本次刷新已保存", N9Monitor.state.value.status)
    }

    @Test fun `destroy joins an uninterruptible read before shutdown and does not record its late result`() {
        N9Monitor.setEnabled(app, true)
        val gate = CompletableDeferred<Unit>()
        adb.blockNext = gate
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        controller!!.destroy()
        controller = null
        scheduler.runCurrent()
        assertEquals(0, adb.shutdowns)
        assertTrue(N9Monitor.state.value.latest!!.values.isEmpty())
        assertFalse(N9Monitor.state.value.running)
        gate.complete(Unit)
        scheduler.runCurrent()
        assertTrue(saved.isEmpty())
        assertEquals(1, adb.shutdowns)
        assertEquals(listOf(0), adb.readsAtShutdown)
        assertTrue(N9Monitor.state.value.status.contains("已中断"))
        verify { store.finishOpen("服务退出，连续记录中断") }
        verify(exactly = 1) { store.close() }
    }

    @Test fun `stop save failure persists disabled but reports failure instead of successful stop`() {
        N9Monitor.setEnabled(app, true)
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        every { store.finishOpen("用户停止自动记录") } throws IOException("disk full")
        service.command(N9MonitorService.ACTION_STOP, 2)
        scheduler.runCurrent()
        assertFalse(N9Monitor.settings(app).enabled)
        assertTrue(N9Monitor.state.value.latest!!.values.isEmpty())
        assertTrue(N9Monitor.state.value.status.contains("保存失败"))
        assertTrue(shadowOf(service).isForegroundStopped)
        val state = N9Monitor.state.value
        destroy()
        assertEquals(state, N9Monitor.state.value)
    }

    @Test fun `explicit enable queued after stop restarts only after the canceled read has exited`() {
        N9Monitor.setEnabled(app, true)
        val gate = CompletableDeferred<Unit>()
        adb.blockNext = gate
        val service = create()
        service.command(N9MonitorService.ACTION_ENABLE, 1)
        scheduler.runCurrent()
        service.command(N9MonitorService.ACTION_STOP, 2)
        service.command(N9MonitorService.ACTION_ENABLE, 3)
        scheduler.runCurrent()
        assertEquals(0, adb.shutdowns)
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(1, adb.shutdowns)
        assertEquals(listOf(0), adb.readsAtShutdown)
        assertEquals(1, saved.size)
        assertTrue(N9Monitor.settings(app).enabled)
        assertTrue(N9Monitor.state.value.running)
        assertFalse(shadowOf(service).isForegroundStopped)
        service.command(N9MonitorService.ACTION_STOP, 4)
        scheduler.runCurrent()
    }

    private class FakeAdb : AdbOnDeviceClient {
        var connected = true
        var connectionFailure = false
        var connects = 0
        var shutdowns = 0
        var blockNext: CompletableDeferred<Unit>? = null
        var blockAtCommand: Int? = null
        var activeGate: CompletableDeferred<Unit>? = null
        var activeReads = 0
        var maxActiveReads = 0
        val readsAtShutdown = mutableListOf<Int>()
        val commands = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> {
            connects++
            connected = !connectionFailure
            return if (connected) Result.success(Unit) else Result.failure(IOException("port closed"))
        }
        override suspend fun isConnected() = connected
        override fun lastConnectFailure() = if (connectionFailure) AdbConnectFailure.UNREACHABLE else null
        override suspend fun exec(cmd: String): String? {
            commands += cmd
            activeReads++
            maxActiveReads = maxOf(maxActiveReads, activeReads)
            try {
                blockNext?.takeIf { blockAtCommand == null || commands.size == blockAtCommand }?.let { gate ->
                    blockNext = null
                    blockAtCommand = null
                    activeGate = gate
                    // Model a socket/SQLite operation that only ends after its own timeout.
                    withContext(NonCancellable) { gate.await() }
                    activeGate = null
                }
                val fid = cmd.substringAfterLast(' ').toInt()
                return when (fid) {
                    FidMap.byField.getValue("soh").fid -> "Result: Parcel(\t00000000 00000064)"
                    FidMap.byField.getValue("soc").fid -> "Result: Parcel(\t00000000 42c40000)"
                    else -> null
                }
            } finally { activeReads-- }
        }
        override suspend fun shutdown() {
            shutdowns++
            readsAtShutdown += activeReads
            connected = false
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun spawnHelper(token: String): Boolean = error("Unexpected Helper")
        override suspend fun killHelper(): Boolean = error("Unexpected Helper")
        override suspend fun readHelperLog(): String? = error("Unexpected Helper")
        override suspend fun helperHeartbeat(): Boolean = error("Unexpected Helper")
    }
}
