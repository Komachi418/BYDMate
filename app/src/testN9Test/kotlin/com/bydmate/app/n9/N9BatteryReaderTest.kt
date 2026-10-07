package com.bydmate.app.n9

import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.nativestack.FidMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class N9BatteryReaderTest {
    private val names = listOf("soh", "soc", "totalElecConsumption", "minCellVoltage", "maxCellVoltage")
    // Actual scalar replies from the first successful N9 test report, 2026-10-07.
    private val samples = listOf(
        "Result: Parcel(\t00000000 00000064   '....d...')",
        "Result: Parcel(\t00000000 42c40000   '.......B')",
        "Result: Parcel(\t00000000 4351e667   '....g.QC')",
        "Result: Parcel(\t00000000 00000cfb   '........')",
        "Result: Parcel(\t00000000 00000d02   '........')",
    )
    private fun command(field: String): String = FidMap.byField.getValue(field).let {
        "service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}"
    }
    private fun parcel(bits: Int) = "Result: Parcel(\t00000000 ${String.format(Locale.ROOT, "%08x", bits)}   '........')"

    private class FakeAdb : AdbOnDeviceClient {
        var connection: Result<Unit> = Result.success(Unit)
        var connected = true
        var connectException: Exception? = null
        var readException: Exception? = null
        var lastFailureException: Exception? = null
        var throwForCommand: String? = null
        val responses = mutableMapOf<String, String?>()
        val commands = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> {
            connectException?.let { throw it }
            return connection
        }
        override suspend fun isConnected() = connected
        override fun lastConnectFailure(): AdbConnectFailure? {
            lastFailureException?.let { throw it }
            return if (connection.isSuccess) null else AdbConnectFailure.UNREACHABLE
        }
        override suspend fun exec(cmd: String): String? {
            commands += cmd
            if (cmd == throwForCommand) readException?.let { throw it }
            return responses[cmd]
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun spawnHelper(token: String): Boolean = error("Unexpected helper start")
        override suspend fun killHelper(): Boolean = error("Unexpected helper stop")
        override suspend fun readHelperLog(): String? = error("Unexpected helper log read")
        override suspend fun helperHeartbeat(): Boolean = error("Unexpected helper heartbeat")
        override suspend fun shutdown() {}
    }

    private fun readyAdb() = FakeAdb().apply {
        names.zip(samples).forEach { (field, raw) -> responses[command(field)] = raw }
    }

    @Test fun `real N9 replies retain raw tabs and read only five known getter addresses`() = runBlocking {
        val adb = readyAdb()
        val snapshot = N9BatteryReader(adb).read()
        assertEquals(names.map(::command), adb.commands)
        assertEquals(98f, snapshot.soc!!, 0f)
        assertEquals(100, snapshot.soh)
        assertEquals(209.90001f, snapshot.totalElecConsumption!!, 0.00001f)
        assertEquals(3323, snapshot.minCellMv)
        assertEquals(3330, snapshot.maxCellMv)
        assertEquals(7, snapshot.deltaMv)
        assertTrue(snapshot.hasReadings)
        samples.forEach { assertTrue(snapshot.diagnostic.contains(it)) }
        assertTrue(snapshot.diagnostic.contains("classic5555 handshake=true connected=true failure=none"))
        assertTrue(snapshot.diagnostic.contains("autoservice probe=VALID_READING"))
        assertTrue(snapshot.toBatteryReportText().contains("3.323 V (3323 mV)"))
    }

    @Test fun `zero SOC and lifetime energy stay valid`() = runBlocking {
        val adb = readyAdb().apply {
            responses[command("soc")] = parcel(0)
            responses[command("totalElecConsumption")] = parcel(0)
        }
        val snapshot = N9BatteryReader(adb).read()
        assertEquals(0f, snapshot.soc!!, 0f)
        assertEquals(0f, snapshot.totalElecConsumption!!, 0f)
        assertTrue(snapshot.hasReadings)
    }

    @Test fun `NaN infinity float and integer sentinels never become valid SOC or energy`() = runBlocking {
        for (bits in listOf(Float.NaN.toRawBits(), Float.POSITIVE_INFINITY.toRawBits(),
            (-1f).toRawBits(), 65535, 1048575, -10013, -10011)) {
            val adb = readyAdb().apply {
                responses[command("soc")] = parcel(bits)
                responses[command("totalElecConsumption")] = parcel(bits)
            }
            val snapshot = N9BatteryReader(adb).read()
            assertNull(snapshot.soc)
            assertNull(snapshot.totalElecConsumption)
            assertEquals(100, snapshot.soh)
            assertTrue(snapshot.diagnostic.contains("autoservice probe=PARTIAL"))
        }
    }

    @Test fun `range failures are independent and never reuse the preceding valid snapshot`() = runBlocking {
        val adb = readyAdb()
        val reader = N9BatteryReader(adb)
        assertEquals(98f, reader.read().soc!!, 0f)
        adb.responses[command("soc")] = parcel(101f.toRawBits())
        adb.responses[command("soh")] = parcel(101)
        adb.responses[command("totalElecConsumption")] = parcel((-0.5f).toRawBits())
        adb.responses[command("minCellVoltage")] = parcel(499)
        adb.responses[command("maxCellVoltage")] = parcel(6001)
        val failed = reader.read()
        assertNull(failed.soc)
        assertNull(failed.soh)
        assertNull(failed.totalElecConsumption)
        assertNull(failed.minCellMv)
        assertNull(failed.maxCellMv)
        assertNull(failed.deltaMv)
        assertFalse(failed.hasReadings)
        assertTrue(failed.diagnostic.contains("autoservice probe=NO_VALID_READING"))
    }

    @Test fun `failed connection after success creates an empty new result and issues no getters`() = runBlocking {
        val adb = readyAdb()
        val reader = N9BatteryReader(adb)
        assertTrue(reader.read().hasReadings)
        adb.connection = Result.failure(IOException("connection lost"))
        adb.connected = false
        adb.commands.clear()
        val failed = reader.read()
        assertFalse(failed.hasReadings)
        assertTrue(adb.commands.isEmpty())
        assertTrue(failed.diagnostic.contains("handshake=false connected=false"))
        assertTrue(failed.diagnostic.contains("autoservice probe=NOT_RUN"))
        assertTrue(failed.diagnostic.contains("connection lost"))
    }

    @Test fun `connection exception or disconnected success yields failed snapshot`() = runBlocking {
        val throwing = readyAdb().apply { connectException = IOException("port closed") }
        assertFalse(N9BatteryReader(throwing).read().hasReadings)
        assertTrue(throwing.commands.isEmpty())
        val disconnected = readyAdb().apply { connected = false }
        assertFalse(N9BatteryReader(disconnected).read().hasReadings)
        assertTrue(disconnected.commands.isEmpty())
    }

    @Test fun `one read exception or malformed Parcel does not discard the other new fields`() = runBlocking {
        val adb = readyAdb().apply {
            throwForCommand = command("soh")
            readException = IOException("read failed")
            responses[command("soc")] = "Result: Parcel(00000001 42c40000)"
        }
        val snapshot = N9BatteryReader(adb).read()
        assertNull(snapshot.soh)
        assertNull(snapshot.soc)
        assertEquals(3323, snapshot.minCellMv)
        assertEquals(3330, snapshot.maxCellMv)
        assertEquals(5, adb.commands.size)
        assertTrue(snapshot.diagnostic.contains("soh read error=IOException"))
        assertTrue(snapshot.diagnostic.contains("autoservice probe=PARTIAL"))
    }

    @Test fun `reversed cell extrema give no delta without hiding measured values`() = runBlocking {
        val adb = readyAdb().apply { responses[command("minCellVoltage")] = parcel(3340) }
        val snapshot = N9BatteryReader(adb).read()
        assertEquals(3340, snapshot.minCellMv)
        assertNull(snapshot.deltaMv)
        assertTrue(snapshot.diagnostic.contains("max below min"))
    }

    @Test fun `cancellation in connection read or failure lookup propagates`() = runBlocking {
        val clients = listOf(
            readyAdb().apply { connectException = CancellationException("closed") },
            readyAdb().apply { throwForCommand = command("soh"); readException = CancellationException("closed") },
            readyAdb().apply { lastFailureException = CancellationException("closed") },
        )
        for (adb in clients) {
            try {
                N9BatteryReader(adb).read()
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(adb.commands.size <= 1)
            }
        }
    }
}
