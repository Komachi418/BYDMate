package com.bydmate.app.n9

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class N9BatteryHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context(dir: File = temp.root): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = dir
        }

    private fun snapshot(time: Long = 1L) = N9BatterySnapshot(
        time, 98f, 100, 209.90001f, 3323, 3330,
        "classic5555 handshake=true\nResult: Parcel(\t00000000 42c40000   '.......B')\n",
    )

    @Test fun `new instance reloads all values and original raw whitespace`() {
        val ctx = context()
        val value = snapshot()
        assertTrue(N9BatteryHistory(ctx).append(value))
        assertEquals(listOf(value), N9BatteryHistory(ctx).load())
    }

    @Test fun `keeps latest twenty acquisitions even if wall clock moves backwards`() {
        val ctx = context()
        val history = N9BatteryHistory(ctx)
        val all = (0 until 25).map { snapshot(1000L - it) }
        all.forEach { assertTrue(history.append(it)) }
        assertEquals(all.takeLast(20), N9BatteryHistory(ctx).load())
    }

    @Test fun `failed refresh is stored as a separate empty record and export retains its error`() {
        val history = N9BatteryHistory(context())
        val failed = N9BatterySnapshot(2L, null, null, null, null, null, "classic5555 handshake=false\nconnection lost")
        assertTrue(history.append(snapshot()))
        assertTrue(history.append(failed))
        val loaded = history.load()
        assertEquals(2, loaded.size)
        assertFalse(loaded.last().hasReadings)
        assertNull(loaded.last().deltaMv)
        val text = n9BatteryHistoryText(loaded)
        assertTrue(text.contains("SOC=不可用"))
        assertTrue(text.contains("connection lost"))
        assertTrue(text.contains("capturedAtMillis=2"))
        assertTrue(text.contains("Parcel(\t00000000 42c40000"))
    }

    @Test fun `corrupt file returns empty and next append starts recoverable history`() {
        val ctx = context()
        File(ctx.filesDir, "n9-battery-history.json").writeText("{broken partial file")
        val history = N9BatteryHistory(ctx)
        assertTrue(history.load().isEmpty())
        assertTrue(history.append(snapshot()))
        assertEquals(listOf(snapshot()), N9BatteryHistory(ctx).load())
    }

    @Test fun `interrupted atomic write recovers previous complete history`() {
        val ctx = context()
        val history = N9BatteryHistory(ctx)
        assertTrue(history.append(snapshot()))
        val writer = AtomicFile(File(ctx.filesDir, "n9-battery-history.json"))
        writer.startWrite().use { it.write("{unfinished".toByteArray(Charsets.UTF_8)) }
        // Simulate process death before finishWrite/failWrite: a fresh reader must recover.
        assertEquals(listOf(snapshot()), N9BatteryHistory(ctx).load())
        assertTrue(history.append(snapshot(2L)))
        assertEquals(listOf(snapshot(), snapshot(2L)), history.load())
    }

    @Test fun `serialization failure returns false and preserves previous saved record`() {
        val ctx = context()
        val history = N9BatteryHistory(ctx)
        assertTrue(history.append(snapshot()))
        assertFalse(history.append(snapshot(2L).copy(soc = Float.NaN)))
        assertEquals(listOf(snapshot()), N9BatteryHistory(ctx).load())
    }

    @Test fun `unwritable history parent returns false and does not crash load`() {
        val history = N9BatteryHistory(context(temp.newFile("not-a-directory")))
        assertFalse(history.append(snapshot()))
        assertTrue(history.load().isEmpty())
    }

    @Test fun `oversized diagnostic is bounded with an explicit marker across reload and export`() {
        val ctx = context()
        val history = N9BatteryHistory(ctx)
        assertTrue(history.append(snapshot().copy(diagnostic = "x".repeat(30_000))))
        val loaded = N9BatteryHistory(ctx).load().single()
        assertEquals(N9_BATTERY_DIAGNOSTIC_LIMIT, loaded.diagnostic.length)
        assertTrue(loaded.diagnostic.contains("diagnostic truncated"))
        assertTrue(loaded.toBatteryReportText().contains("diagnostic truncated"))
    }
}
