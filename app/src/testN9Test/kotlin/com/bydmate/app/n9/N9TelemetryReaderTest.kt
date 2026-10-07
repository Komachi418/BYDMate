package com.bydmate.app.n9

import android.os.SystemClock
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
class N9TelemetryReaderTest {
    private val names = listOf(
        "soh", "soc", "totalElecConsumption", "minCellVoltage", "maxCellVoltage",
        "batteryRemainKwh", "voltage12v", "hvVoltage", "hvCurrent", "maxBatTemp", "minBatTemp",
        "insulationKohm", "bmsMaxChargeKw", "bmsMaxDischargeAllowKw", "bmsMaxDischargeKw",
        "chargeGunState", "bmsState", "chargingType", "chargingCapacity", "chargerConnectState",
        "chargeConnectIndicator", "mileage", "speed", "powerState", "motorCurrentFront",
        "motorCurrentRear", "compressorW", "power", "batteryType", "keyBatteryStatus",
    )
    // Exact scalar replies from the first successful N9 report, 2026-10-07.
    private val v1Raw = listOf(
        "Result: Parcel(\t00000000 00000064   '....d...')",
        "Result: Parcel(\t00000000 42c40000   '.......B')",
        "Result: Parcel(\t00000000 4351e667   '....g.QC')",
        "Result: Parcel(\t00000000 00000cfb   '........')",
        "Result: Parcel(\t00000000 00000d02   '........')",
    )

    private fun command(field: String) = FidMap.byField.getValue(field).let {
        "service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}"
    }
    private fun parcel(bits: Int) = "Result: Parcel(\t00000000 ${String.format(Locale.ROOT, "%08x", bits)}   '........')"

    private class FakeAdb : AdbOnDeviceClient {
        var connected = true
        var connection: Result<Unit> = Result.success(Unit)
        var connectionException: Exception? = null
        var readException: Exception? = null
        var failureLookupException: Exception? = null
        var throwForCommand: String? = null
        var connectCount = 0
        var shutdownCount = 0
        val replies = mutableMapOf<String, String?>()
        val commands = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> {
            connectCount++
            connectionException?.let { throw it }
            connected = connection.isSuccess
            return connection
        }
        override suspend fun isConnected() = connected
        override fun lastConnectFailure(): AdbConnectFailure? {
            failureLookupException?.let { throw it }
            return if (connection.isFailure) AdbConnectFailure.UNREACHABLE else null
        }
        override suspend fun exec(cmd: String): String? {
            commands += cmd
            if (cmd == throwForCommand) readException?.let { throw it }
            return replies[cmd]
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun spawnHelper(token: String): Boolean = error("Unexpected Helper")
        override suspend fun killHelper(): Boolean = error("Unexpected Helper")
        override suspend fun readHelperLog(): String? = error("Unexpected Helper")
        override suspend fun helperHeartbeat(): Boolean = error("Unexpected Helper")
        override suspend fun shutdown() { shutdownCount++ }
    }

    private fun ready() = FakeAdb().apply {
        names.take(5).zip(v1Raw).forEach { (field, raw) -> replies[command(field)] = raw }
    }
    private fun FakeAdb.int(field: String, value: Int) { replies[command(field)] = parcel(value) }
    private fun FakeAdb.float(field: String, value: Float) { int(field, value.toRawBits()) }

    @Test fun `V1 values retain original tabs and only fixed getter commands run in priority order`() = runBlocking {
        val adb = ready()
        val sample = N9TelemetryReader(adb).read()
        assertEquals(names.map(::command), adb.commands)
        assertTrue(adb.commands.all { it.matches(Regex("^service call autoservice [57] i32 \\d+ i32 -?\\d+$")) })
        assertFalse(adb.commands.contains(command("chargeBatteryVolt")))
        assertEquals(100.0, sample.values.getValue("soh"), 0.0)
        assertEquals(98.0, sample.values.getValue("soc"), 0.0)
        assertEquals(209.90001, sample.values.getValue("totalElecConsumption"), 0.00001)
        assertEquals(3.323, sample.values.getValue("minCellVoltage"), 0.000001)
        assertEquals(3.330, sample.values.getValue("maxCellVoltage"), 0.000001)
        assertEquals(7.0, sample.values.getValue("cellDeltaMv"), 0.000001)
        assertEquals(6, sample.values.size)
        v1Raw.forEach { assertTrue(sample.raw.contains(it)) }
        assertTrue(sample.timestamp > 0)
        assertEquals(SystemClock.elapsedRealtime(), sample.monotonicMs)
        assertNull(sample.connectionError)
        assertEquals(0, adb.connectCount)
        assertEquals(0, adb.shutdownCount)
    }

    @Test fun `extended scales temperature offset and signed electrical products are explicit`() = runBlocking {
        val adb = ready().apply {
            int("hvVoltage", 600); float("hvCurrent", -10.5f)
            float("motorCurrentFront", 20f); float("motorCurrentRear", -2f)
            int("maxBatTemp", 70); int("minBatTemp", 61)
            int("bmsMaxChargeKw", 123); int("bmsMaxDischargeAllowKw", 456)
            int("bmsMaxDischargeKw", 78); int("mileage", 12345)
            int("compressorW", 1500); int("power", -12)
            float("speed", 42.5f); float("voltage12v", 13.2f)
        }
        val v = N9TelemetryReader(adb).read().values
        assertEquals(30.0, v.getValue("maxBatTemp"), 0.0)
        assertEquals(21.0, v.getValue("minBatTemp"), 0.0)
        assertEquals(9.0, v.getValue("tempDeltaC"), 0.0)
        assertEquals(12.3, v.getValue("bmsMaxChargeKw"), 0.000001)
        assertEquals(45.6, v.getValue("bmsMaxDischargeAllowKw"), 0.000001)
        assertEquals(78.0, v.getValue("bmsMaxDischargeKw"), 0.0)
        assertEquals(1234.5, v.getValue("mileage"), 0.0)
        assertEquals(-6.3, v.getValue("batteryPowerKw"), 0.000001)
        assertEquals(12.0, v.getValue("frontPowerKw"), 0.000001)
        assertEquals(-1.2, v.getValue("rearPowerKw"), 0.000001)
        assertEquals(1.5, v.getValue("compressorKw"), 0.0)
        assertEquals(-12.0, v.getValue("power"), 0.0)
        assertEquals(42.5, v.getValue("speed"), 0.0)
        assertEquals(13.2, v.getValue("voltage12v"), 0.00001)
    }

    @Test fun `zero SOC energy current and power remain valid without inventing cell voltages`() = runBlocking {
        val adb = FakeAdb().apply { names.forEach { int(it, 0) } }
        val sample = N9TelemetryReader(adb).read()
        for (key in listOf("soc", "soh", "totalElecConsumption", "batteryRemainKwh", "hvCurrent",
            "batteryPowerKw", "frontPowerKw", "rearPowerKw", "compressorKw", "power", "speed")) {
            assertEquals(key, 0.0, sample.values.getValue(key), 0.0)
        }
        assertFalse(sample.values.containsKey("minCellVoltage"))
        assertFalse(sample.values.containsKey("cellDeltaMv"))
        assertTrue(sample.hasReadings)
    }

    @Test fun `bad float words status replies and ranges never become valid samples or derived powers`() = runBlocking {
        val invalidFloats = listOf(Float.NaN.toRawBits(), Float.POSITIVE_INFINITY.toRawBits(),
            Float.NEGATIVE_INFINITY.toRawBits(), (-1f).toRawBits(), 65535, 1048575, -10013, -10011)
        for (word in invalidFloats) {
            val adb = ready().apply { int("soc", word); int("hvCurrent", word); int("hvVoltage", 600) }
            val sample = N9TelemetryReader(adb).read()
            assertFalse(sample.values.containsKey("soc"))
            assertFalse(sample.values.containsKey("hvCurrent"))
            assertFalse(sample.values.containsKey("batteryPowerKw"))
        }
        val adb = ready().apply {
            int("soh", 101); float("soc", -0.5f); int("hvVoltage", 1001)
            float("hvCurrent", 2001f); int("maxBatTemp", 121); int("compressorW", 20001)
            int("bmsMaxChargeKw", 10001); int("mileage", 100000001)
            float("speed", 401f); int("powerState", 3)
            replies[command("batteryType")] = "Result: Parcel(\t00000001 00000001)"
        }
        val sample = N9TelemetryReader(adb).read()
        for (key in listOf("soh", "soc", "hvVoltage", "hvCurrent", "maxBatTemp", "compressorW",
            "bmsMaxChargeKw", "mileage", "speed", "powerState", "batteryType", "batteryPowerKw")) {
            assertFalse(key, sample.values.containsKey(key))
        }
        assertTrue(sample.raw.contains("00000001 00000001"))
        assertTrue(sample.raw.contains("decoded=1001.0"))
    }

    @Test fun `reversed extrema and missing factors omit derived readings instead of filling old values`() = runBlocking {
        val adb = ready().apply {
            int("minCellVoltage", 3340); int("maxBatTemp", 60); int("minBatTemp", 70)
            float("motorCurrentFront", 5f); float("batteryRemainKwh", 0f)
        }
        val sample = N9TelemetryReader(adb).read()
        for (key in listOf("cellDeltaMv", "tempDeltaC", "frontPowerKw", "batteryRemainKwh")) {
            assertFalse(sample.values.containsKey(key))
        }
        assertEquals(3.340, sample.values.getValue("minCellVoltage"), 0.000001)
        assertTrue(sample.raw.contains("max below min"))
        assertTrue(sample.raw.contains("zero energy with positive SOC"))
    }

    @Test fun `reader reuses connection then reconnects only after disconnect and returns new empty failure`() = runBlocking {
        val adb = ready()
        val reader = N9TelemetryReader(adb)
        assertTrue(reader.read().hasReadings)
        assertEquals(0, adb.connectCount)
        adb.connected = false
        assertTrue(reader.read().hasReadings)
        assertEquals(1, adb.connectCount)
        adb.connected = false
        adb.connection = Result.failure(IOException("port closed"))
        adb.commands.clear()
        val failed = reader.read()
        assertFalse(failed.hasReadings)
        assertTrue(failed.values.isEmpty())
        assertEquals(2, adb.connectCount)
        assertTrue(adb.commands.isEmpty())
        assertTrue(failed.connectionError!!.contains("port closed"))
        assertTrue(failed.raw.contains("autoservice probe=NOT_RUN"))
        assertEquals(0, adb.shutdownCount)
    }

    @Test fun `missing and throwing fields after success have no stale values while later reads continue`() = runBlocking {
        val adb = ready()
        val reader = N9TelemetryReader(adb)
        assertTrue(reader.read().values.containsKey("soc"))
        adb.replies.remove(command("soc"))
        adb.throwForCommand = command("soh")
        adb.readException = IOException("specific read failed")
        adb.commands.clear()
        val next = reader.read()
        assertFalse(next.values.containsKey("soc"))
        assertFalse(next.values.containsKey("soh"))
        assertEquals(30, adb.commands.size)
        assertEquals(7.0, next.values.getValue("cellDeltaMv"), 0.000001)
        assertTrue(next.raw.contains("IOException: specific read failed"))
    }

    @Test fun `cancelled connection failure lookup and field reads propagate without more commands`() = runBlocking {
        val cases = listOf(
            ready().apply { connected = false; connectionException = CancellationException("closed") },
            ready().apply { connected = false; connection = Result.failure(CancellationException("closed")) },
            ready().apply {
                connected = false; connection = Result.failure(IOException("gone"))
                failureLookupException = CancellationException("closed")
            },
            ready().apply { throwForCommand = command("soh"); readException = CancellationException("closed") },
        )
        for (adb in cases) {
            try {
                N9TelemetryReader(adb).read()
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(adb.commands.size <= 1)
            }
        }
    }

    @Test fun `diagnostic raw is bounded explicitly without interrupting remaining getter reads`() = runBlocking {
        val adb = ready().apply { replies[command("soh")] = "x".repeat(30_000) }
        val sample = N9TelemetryReader(adb).read()
        assertTrue(sample.raw.length <= 16_384)
        assertTrue(sample.raw.contains("raw diagnostic truncated"))
        assertEquals(30, adb.commands.size)
        assertEquals(98.0, sample.values.getValue("soc"), 0.0)
    }

    @Test fun `field metadata distinguishes true N9 evidence from expanded mappings and electrical estimates`() {
        val fields = N9Fields.definitions
        assertEquals(fields.size, fields.map { it.key }.toSet().size)
        assertEquals((names.take(5) + "cellDeltaMv").toSet(), fields.filter { it.verifiedOnN9 }.map { it.key }.toSet())
        assertEquals(36, fields.size)
        assertEquals("V", fields.single { it.key == "minCellVoltage" }.unit)
        assertEquals("kW", fields.single { it.key == "batteryPowerKw" }.unit)
        assertFalse(fields.single { it.key == "frontPowerKw" }.verifiedOnN9)
        assertFalse(fields.any { "纯回收" in it.label })
    }
}
