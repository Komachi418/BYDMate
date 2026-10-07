package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AutoserviceClientImplTest {

    private class FakeAdb(
        val responses: Map<String, String?>,
        val connected: Boolean = true
    ) : AdbOnDeviceClient {
        val calls = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = connected
        override fun lastConnectFailure(): AdbConnectFailure? = null
        override suspend fun exec(cmd: String): String? {
            calls += cmd
            return responses[cmd]
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = true
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = true
        override suspend fun spawnHelper(token: String): Boolean = false
        override suspend fun killHelper(): Boolean = false
        override suspend fun readHelperLog(): String? = null
        override suspend fun helperHeartbeat(): Boolean = false
        override suspend fun shutdown() {}
    }

    private fun parcelInt(value: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(value)

    private fun parcelFloat(valueBits: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(valueBits)

    @Test
    fun `getInt returns parsed value when ADB returns valid Parcel`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1246777400"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(91)))
        val client = AutoserviceClientImpl(adb)

        val result = client.getInt(dev = 1014, fid = 1246777400)

        assertEquals(91, result)
        assertEquals(listOf(cmd), adb.calls)
    }

    @Test
    fun `N9 Android 14 original replies decode SoH lifetime energy and SOC`() = runTest {
        // Exact Result substrings (including literal tabs) from lines 427-429 of
        // bydmate_logs_20261007_185755.txt, SHA-256:
        // bbe03154e7267ad9b60e41d4a34e6c215344c270d4de6adfb0fa443fcfc5cfd4
        // Keep only these scalar replies in the fixture, not the private diagnostic log.
        val samples = checkNotNull(javaClass.getResource("/autoservice/n9-android14-parcels.txt"))
            .readText().lines().filter { it.isNotEmpty() }
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.startsWith("Result: Parcel(\t") })
        val commands = listOf(
            "service call autoservice 5 i32 1014 i32 1145045032",
            "service call autoservice 7 i32 1014 i32 1032871984",
            "service call autoservice 7 i32 1014 i32 1246777400"
        )
        val adb = FakeAdb(responses = commands.zip(samples).toMap())
        val client = AutoserviceClientImpl(adb)

        assertEquals(100, client.getInt(1014, 1145045032))
        assertEquals(209.600006f, client.getFloat(1014, 1032871984)!!, 0.000001f)
        assertEquals(99.0f, client.getFloat(1014, 1246777400)!!, 0.0f)
        assertEquals(commands, adb.calls)
        assertTrue(client.isAvailable())
    }

    @Test
    fun `scalar replies accept spaces tabs and line breaks before exception word`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1145045032"
        for (whitespace in listOf("", " ", "\t", " \t ", "\r\n\t")) {
            val raw = "Result: Parcel(${whitespace}00000000\t00000064   '....d...')\r\n"
            val client = AutoserviceClientImpl(FakeAdb(mapOf(cmd to raw)))
            assertEquals("whitespace=$whitespace", 100, client.getInt(1014, 1145045032))
        }
    }

    @Test
    fun `scalar replies reject exceptions malformed words and truncated output`() = runTest {
        val intCmd = "service call autoservice 5 i32 1014 i32 1145045032"
        val floatCmd = "service call autoservice 7 i32 1014 i32 1246777400"
        val invalid = listOf(
            "Result: Parcel(\tfffffffe 00000064   '........')",
            "Result: Parcel(\t00000001 00000064   '........')",
            "Result: Parcel(\t000000000 00000064   '........')",
            "Result: Parcel(\t00000000 0000064   '........')",
            "Result: Parcel(\t00000000 000000640   '........')",
            "Result: Parcel(\t00000000 0000006g   '........')",
            "Result: Parcel(\t00000000)",
            "Result: Parcel(\t00000000 00000064",
            "Result: Parcel(\t00000000 00000064   '....d...'",
            "Result: Parcel(\t00000000 00000064   '....d...)",
            "Result: Parcel(\t00000000 00000064garbage)",
            "service: Service autoservice does not exist"
        )
        for (raw in invalid) {
            val client = AutoserviceClientImpl(FakeAdb(mapOf(intCmd to raw, floatCmd to raw)))
            assertNull(raw, client.getInt(1014, 1145045032))
            assertNull(raw, client.getFloat(1014, 1246777400))
        }
    }

    @Test
    fun `Android 14 whitespace keeps sentinel decoding and raw access unchanged`() = runTest {
        val intCmd = "service call autoservice 5 i32 1014 i32 1145045032"
        for (sentinel in listOf(0xFFFF, 0xFFFFF, -10013, -10011)) {
            val raw = parcelInt(sentinel).replace("Parcel(", "Parcel(\t")
            val client = AutoserviceClientImpl(FakeAdb(mapOf(intCmd to raw)))
            assertNull(client.getInt(1014, 1145045032))
            assertEquals(sentinel, client.getIntRaw(1014, 1145045032))
        }
        val floatCmd = "service call autoservice 7 i32 1014 i32 1246777400"
        for (bits in listOf(0xBF800000.toInt(), 0x7FC00000, 0x7F800000, 0xFF800000.toInt())) {
            val raw = parcelFloat(bits).replace("Parcel(", "Parcel( \t")
            val client = AutoserviceClientImpl(FakeAdb(mapOf(floatCmd to raw)))
            assertNull(client.getFloat(1014, 1246777400))
        }
    }

    @Test
    fun `getInt returns null when ADB returns sentinel 0xFFFF`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 99999"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(0xFFFF)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 99999))
    }

    @Test
    fun `getInt returns null when ADB returns -10011 wrong direction`() = runTest {
        val cmd = "service call autoservice 5 i32 1015 i32 12345"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(-10011)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1015, fid = 12345))
    }

    @Test
    fun `getInt returns null when ADB exec returns null`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1246777400"
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 1246777400))
        assertEquals(listOf(cmd), adb.calls)
    }

    @Test
    fun `getFloat parses IEEE 754 bits and returns Float`() = runTest {
        // 91.0f as int bits = 0x42B60000 = 1119223808 — using a known float fid (LIFETIME_KWH).
        val cmd = "service call autoservice 7 i32 1014 i32 1032871984"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0x42B60000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertEquals(91.0f, client.getFloat(dev = 1014, fid = 1032871984)!!, 0.001f)
    }

    @Test
    fun `getFloat returns null on -1f sentinel (0xBF800000)`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0xBF800000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getFloat(dev = 1014, fid = FidRegistry.FID_LIFETIME_KWH))
    }

    @Test
    fun `readBatterySnapshot wires every fid with correct transact and dev`() = runTest {
        // SoH (1145045032) is an INT fid (tx=5) — int 100 = 0x64.
        // LIFETIME_MILEAGE (1246765072) is an INT fid (tx=5) returned ×10 — 21165 raw → 2116.5 km.
        // SOC, LIFETIME_KWH, OTA_12V are FLOAT fids (tx=7).
        // dev=1001 for bodywork (12V), dev=1014 for statistic.
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelInt(100),
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOC}" to parcelFloat(0x42B60000.toInt()),  // 91f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0x4416B333.toInt()),  // ~602.7f
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_LIFETIME_MILEAGE}" to parcelInt(21165),  // 2116.5 km
            "service call autoservice 7 i32 1001 i32 ${FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE}" to parcelFloat(0x41600000.toInt())  // 14.0f
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readBatterySnapshot()

        assertEquals(100.0f, snap!!.sohPercent!!, 0.01f)
        assertEquals(91.0f, snap.socPercent!!, 0.01f)
        assertEquals(602.7f, snap.lifetimeKwh!!, 0.1f)
        assertEquals(2116.5f, snap.lifetimeMileageKm!!, 0.01f)  // 21165 / 10
        assertEquals(14.0f, snap.voltage12v!!, 0.01f)
        assertTrue(snap.readAtMs > 0)
    }

    @Test
    fun `readBatterySnapshot returns null lifetimeMileageKm on sentinel`() = runTest {
        // 0x000FFFFF = 1048575 = "20-bit not initialized" sentinel for fresh-out-of-factory cars.
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelInt(100),
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOC}" to parcelFloat(0x42B60000.toInt()),
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0x4416B333.toInt()),
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_LIFETIME_MILEAGE}" to parcelInt(0x000FFFFF),
            "service call autoservice 7 i32 1001 i32 ${FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE}" to parcelFloat(0x41600000.toInt())
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readBatterySnapshot()
        assertNull(snap!!.lifetimeMileageKm)
    }

    @Test
    fun `readBatterySnapshot returns null when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.readBatterySnapshot())
    }

    @Test
    fun `readChargingSnapshot aggregates gun_state and type at dev 1009`() = runTest {
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1009 i32 ${FidRegistry.FID_GUN_CONNECT_STATE}" to parcelInt(2),
            "service call autoservice 5 i32 1009 i32 ${FidRegistry.FID_CHARGING_TYPE}" to parcelInt(2),
            "service call autoservice 5 i32 1009 i32 ${FidRegistry.FID_CHARGE_BATTERY_VOLT}" to parcelInt(512),
            "service call autoservice 5 i32 1009 i32 ${FidRegistry.FID_BATTERY_TYPE}" to parcelInt(1)
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readChargingSnapshot()

        assertEquals(2, snap!!.gunConnectState)
        assertEquals(2, snap.chargingType)
        assertEquals(512, snap.chargeBatteryVoltV)
        assertEquals(1, snap.batteryType)
        assertTrue(snap.readAtMs > 0)
    }

    @Test
    fun `isAvailable returns false when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertEquals(false, client.isAvailable())
    }

    @Test
    fun `isAvailable returns true when ADB connected and SoH read succeeds via getInt`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(100)))
        val client = AutoserviceClientImpl(adb)

        assertEquals(true, client.isAvailable())
    }

    @Test
    fun `isAvailable returns false when SoH read returns sentinel`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(0xFFFF)))
        val client = AutoserviceClientImpl(adb)

        // Connected but SoH returns FEATURE_LINK_ERROR → all 3 fallback fids unavailable
        // (lifetime_kwh and SOC return null from exec since not in map) → false.
        assertEquals(false, client.isAvailable())
    }

    @Test
    fun `isAvailable returns true when SoH is sentinel but lifetime_kwh works`() = runTest {
        // Simulates BMS-calibrating-after-full-charge: SoH probe returns FEATURE_LINK_ERROR
        // sentinel for ~30 sec but lifetime_kwh stays valid. Without 3-fid fallback the user
        // would see "ADB не отвечает" while catch-up still works.
        val adb = FakeAdb(responses = mapOf(
            // SoH int returns 0xFFFF = FEATURE_LINK_ERROR sentinel → null
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelInt(0xFFFF),
            // lifetime_kwh float returns valid bits 0x44197A66 ≈ 612.6f → non-null → true
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0x44197A66)
        ))
        val client = AutoserviceClientImpl(adb)

        assertTrue(client.isAvailable())
    }

    @Test
    fun `isAvailable returns false when all 3 probe fids return sentinel`() = runTest {
        // SoH (int) → FEATURE_LINK_ERROR (0x0000FFFF); lifetime_kwh + SOC (float) → -1.0f
        // sentinel (0xBF800000). All three return null → isAvailable() = false.
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelInt(0xFFFF),
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0xBF800000.toInt()),
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOC}" to parcelFloat(0xBF800000.toInt())
        ))
        val client = AutoserviceClientImpl(adb)

        assertFalse(client.isAvailable())
    }
}
