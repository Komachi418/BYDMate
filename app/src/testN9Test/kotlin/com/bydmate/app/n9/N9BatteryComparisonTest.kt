package com.bydmate.app.n9

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.vehicle.HelperClientImpl
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Actual production shell parser and HelperClient batch wire decoder, no live ADB/car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class N9BatteryComparisonTest {
    private val names = listOf("soh", "soc", "totalElecConsumption", "minCellVoltage", "maxCellVoltage")
    private val fields = names.map { FidMap.byField.getValue(it) }
    private val validBits = mapOf("soh" to 100, "soc" to 98f.toRawBits(),
        "totalElecConsumption" to 209.9f.toRawBits(), "minCellVoltage" to 3323, "maxCellVoltage" to 3330)

    private fun parcel(bits: Int) = "Result: Parcel(\t00000000 ${String.format(Locale.ROOT, "%08x", bits)}   '........')"

    private abstract class FakeBinder : IBinder {
        override fun isBinderAlive() = true
        override fun pingBinder() = true
        override fun getInterfaceDescriptor() = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
    }

    private class FakeAdb(val reply: (String) -> String?) : AdbOnDeviceClient {
        override suspend fun exec(cmd: String) = reply(cmd)
        override suspend fun connect() = Result.success(Unit)
        override suspend fun isConnected() = true
        override fun lastConnectFailure(): AdbConnectFailure? = null
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = error("Unexpected grant")
        override suspend fun spawnHelper(token: String): Boolean = error("Unexpected spawn")
        override suspend fun killHelper(): Boolean = error("Unexpected kill")
        override suspend fun readHelperLog(): String? = error("Unexpected log read")
        override suspend fun helperHeartbeat(): Boolean = error("Unexpected heartbeat")
        override suspend fun shutdown() {}
    }

    private fun comparison(
        direct: Map<String, Int> = validBits,
        helper: Map<String, Pair<Int, Int>> = validBits.mapValues { 0 to it.value },
        events: MutableList<String> = mutableListOf(),
        wireMode: String = "valid",
        rawOverride: String? = null,
    ): N9BatteryComparison {
        val adb = FakeAdb { command ->
            val field = fields.single { command == "service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}" }
            events += "direct:${field.field}"
            rawOverride ?: direct[field.field]?.let(::parcel)
        }
        val binder = object : FakeBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // A setter, unlisted address, altered descriptor or batch size must fail this test.
                assertEquals(HelperBinderProtocol.TX_READ_BATCH, code)
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                assertEquals(1, data.readInt())
                val tx = data.readInt()
                val dev = data.readInt()
                val fid = data.readInt()
                val field = fields.single { it.transact == tx && it.device == dev && it.fid == fid }
                assertEquals(0, data.dataAvail())
                events += "Helper:${field.field}"
                if (wireMode == "rejected") return false
                val pair = helper[field.field] ?: return false
                reply!!
                reply.writeInt(if (wireMode == "wrongCount") 2 else 1)
                reply.writeInt(pair.first)
                if (wireMode != "truncated") reply.writeInt(pair.second)
                reply.setDataPosition(0)
                return true
            }
        }
        val client = object : HelperClientImpl() {
            override fun resolveBinder(): IBinder = binder
        }
        return N9BatteryComparison(adb, client)
    }

    @Test fun `five real getters interleave paths and decode float bits and seven mV cells`() = runBlocking {
        val events = mutableListOf<String>()
        val result = comparison(events = events).read()
        assertEquals(names.flatMap { listOf("direct:$it", "Helper:$it") }, events)
        assertTrue(result.readings.all { it.observation == "SAME" })
        assertEquals(98.0, result.readings[1].helper.value!!, 0.0)
        assertEquals(209.9, result.readings[2].helper.value!!, 0.00002)
        for (path in listOf(false, true)) {
            assertEquals(3.323, result.cellRange(path).minV!!, 0.000001)
            assertEquals(3.330, result.cellRange(path).maxV!!, 0.000001)
            assertEquals(7.0, result.cellRange(path).deltaMv!!, 0.000001)
        }
        assertTrue(result.readings[0].direct.raw.contains("Parcel(\t00000000 00000064"))
        assertTrue(result.toReportText().contains("valid pairs=5/5; same=5; different=0"))
    }

    @Test fun `zero SOC and energy are valid but zero cell voltage is unavailable`() = runBlocking {
        val zero = validBits.mapValues { 0 }
        val result = comparison(zero, zero.mapValues { 0 to it.value }).read()
        result.readings.take(3).forEach { assertEquals("SAME", it.observation); assertEquals(0.0, it.helper.value!!, 0.0) }
        result.readings.drop(3).forEach { assertEquals("UNAVAILABLE", it.observation) }
        assertNull(result.cellRange(true).deltaMv)
    }

    @Test fun `nonzero autoservice status cannot turn reply zero into a valid battery reading`() = runBlocking {
        for (status in listOf(-10013, -1, 1)) {
            val result = comparison(helper = validBits.mapValues { status to 0 }).read()
            assertTrue(result.readings.all { it.helper.status == status && it.observation == "UNAVAILABLE" })
            assertTrue(result.readings.all { it.helper.value == null && it.helper.error == "AUTOSERVICE_STATUS_ERROR" })
            assertNull(result.cellRange(true).deltaMv)
        }
    }

    @Test fun `rejected truncated and wrong count Binder replies stay unavailable`() = runBlocking {
        for (mode in listOf("rejected", "truncated", "wrongCount")) {
            val result = comparison(wireMode = mode).read()
            assertTrue(result.readings.all { it.helper.error == "HELPER_TRANSPORT_OR_REPLY_ERROR" })
            assertTrue(result.readings.all { it.observation == "UNAVAILABLE" })
        }
    }

    @Test fun `integer sentinels cannot masquerade as small positive floats`() = runBlocking {
        for (bits in listOf(65535, 1048575, -10013, -10011)) {
            val values = validBits.mapValues { bits }
            val result = comparison(values, values.mapValues { 0 to it.value }).read()
            assertTrue(result.readings.all { !it.direct.valid && !it.helper.valid && it.observation == "UNAVAILABLE" })
            assertTrue(result.readings.all { it.helper.error == "INTEGER_SENTINEL" })
        }
    }

    @Test fun `float sentinels NaN infinity and out of range percentages stay unavailable`() = runBlocking {
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 100.01f)) {
            val values = validBits + ("soc" to value.toRawBits())
            val soc = comparison(values, values.mapValues { 0 to it.value }).read().readings.single { it.field.field == "soc" }
            assertFalse(soc.direct.valid)
            assertFalse(soc.helper.valid)
            assertEquals("UNAVAILABLE", soc.observation)
        }
    }

    @Test fun `both missing or malformed direct reply never compare as same`() = runBlocking {
        val missing = comparison(emptyMap(), emptyMap()).read()
        assertTrue(missing.readings.all { it.observation == "UNAVAILABLE" && it.direct.error == "NO_ADB_REPLY" })
        val malformed = comparison(rawOverride = "Result: Parcel(00000001 00000000)").read()
        assertTrue(malformed.readings.all { it.observation == "UNAVAILABLE" && !it.direct.valid })
    }

    @Test fun `changed sequential SOC is reported as different while reversed cell extrema have no delta`() = runBlocking {
        val helper = validBits + ("soc" to 97f.toRawBits()) + ("minCellVoltage" to 3340)
        val result = comparison(helper = helper.mapValues { 0 to it.value }).read()
        assertEquals("DIFFERENT", result.readings.single { it.field.field == "soc" }.observation)
        assertNull(result.cellRange(true).deltaMv)
        assertEquals("MAX_BELOW_MIN", result.cellRange(true).error)
        assertNotNull(result.cellRange(false).deltaMv)
        assertTrue(result.toReportText().contains("顺序读取不保证同一时刻"))
    }

    @Test fun `reader failure is recorded and remaining known fields continue with read time windows`() = runBlocking {
        var clock = 10L
        val seen = mutableListOf<String>()
        val comparator = N9BatteryComparison(
            readDirect = { throw IllegalStateException("transport failed") },
            readHelper = { field -> seen += field.field; N9BatteryReading(0.0, "test", 0) },
            nowMs = { clock++ },
        )
        val result = comparator.read()
        assertEquals(names, seen)
        assertTrue(result.readings.all { it.observation == "UNAVAILABLE" })
        assertEquals("READ_EXCEPTION:IllegalStateException", result.readings[0].direct.error)
        assertEquals(10L, result.readings[0].direct.startedAtMs)
        assertEquals(11L, result.readings[0].direct.finishedAtMs)
        assertEquals(12L, result.readings[0].helper.startedAtMs)
    }

    @Test fun `cancellation stops without starting another read`() = runBlocking {
        var helperReads = 0
        try {
            N9BatteryComparison(
                readDirect = { throw CancellationException("closed") },
                readHelper = { helperReads++; N9BatteryReading(0.0, "test", 0) },
            ).read()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, helperReads)
        }
    }
}
