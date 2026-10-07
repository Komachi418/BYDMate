package com.bydmate.app.n9

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class N9RecordingStoreTest {
    private lateinit var context: Context
    private lateinit var store: N9RecordingStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(N9RecordingStore.DATABASE_NAME)
        store = N9RecordingStore(context)
    }
    @After fun cleanup() {
        store.close()
        context.deleteDatabase(N9RecordingStore.DATABASE_NAME)
    }

    private fun sample(seconds: Long, values: Map<String, Double>, raw: String = "raw-$seconds") =
        N9Sample(1_700_000_000_000L + seconds * 1000, 10_000L + seconds * 1000, values, raw)
    private fun charge(seconds: Long, soc: Double = 20.0, power: Double = -12.0, gun: Double = 2.0) =
        sample(seconds, mapOf("bmsState" to 1.0, "chargeGunState" to gun, "soc" to soc, "soh" to 100.0,
            "batteryPowerKw" to power, "speed" to 0.0))
    private fun trip(seconds: Long, mileage: Double = 100.0, power: Double = 60.0, speed: Double = 20.0) =
        sample(seconds, mapOf("speed" to speed, "mileage" to mileage, "batteryPowerKw" to power, "soc" to 50.0))
    private fun N9Sample.with(vararg values: Pair<String, Double>) = copy(values = this.values + values)
    private fun only(kind: String) = store.sessions(kind).single()

    @Test fun `charge needs two confirmed samples and preserves the first point`() {
        store.record(charge(0))
        assertTrue(store.sessions().isEmpty())
        store.record(charge(15, 20.1))
        val session = only("charge")
        assertEquals("active", session.status)
        assertEquals(charge(0).timestamp, session.startedAt)
        assertEquals(listOf(charge(0).timestamp, charge(15).timestamp), store.points(session.id).map { it.timestamp })
        assertEquals(0.05, session.metrics.getValue("batteryInKwh"), 1e-9)
        assertEquals(1.0, session.metrics.getValue("coverageBattery"), 1e-9)
    }

    @Test fun `SOC rise or negative power alone never creates a charging record`() {
        store.record(sample(0, mapOf("soc" to 20.0, "batteryPowerKw" to -60.0)))
        store.record(sample(15, mapOf("soc" to 50.0, "batteryPowerKw" to -60.0)))
        assertTrue(store.sessions().isEmpty())
    }

    @Test fun `VTOL overrides fallback plugged evidence and never starts charging`() {
        store.record(charge(0, gun = 5.0).with("chargerConnectState" to 1.0))
        store.record(charge(15, gun = 5.0).with("chargeConnectIndicator" to 1.0))
        assertTrue(store.sessions("charge").isEmpty())
        store.record(charge(30))
        store.record(charge(45))
        store.record(charge(60, gun = 5.0))
        assertEquals("interrupted", only("charge").status)
        assertTrue(only("charge").note.contains("VTOL"))
    }

    @Test fun `unknown gun codes cannot start or end a charge`() {
        for (i in 0L..3L) store.record(charge(i * 15, gun = if (i < 2) 0.0 else 99.0))
        assertTrue(store.sessions().isEmpty())
        store.record(charge(60))
        store.record(charge(75))
        store.record(sample(90, mapOf("soc" to 21.0, "chargeGunState" to 99.0)))
        store.record(sample(105, mapOf("soc" to 21.0)))
        assertEquals("active", only("charge").status)
    }

    @Test fun `fallback only plug does not unplug when fallback readings go missing`() {
        store.record(charge(0, gun = 1.0).with("chargerConnectState" to 1.0))
        store.record(charge(15, gun = 1.0).with("chargerConnectState" to 1.0))
        store.record(charge(30, gun = 1.0))
        store.record(charge(45, gun = 1.0))
        assertEquals("active", only("charge").status)
        store.record(sample(60, mapOf("chargeGunState" to 1.0, "chargerConnectState" to 0.0)))
        assertEquals("active", only("charge").status)
        store.record(sample(75, mapOf("chargeGunState" to 1.0, "chargerConnectState" to 0.0)))
        assertEquals("complete", only("charge").status)
    }

    @Test fun `pause keeps session and positive power is not charged energy`() {
        store.record(charge(0))
        store.record(charge(15))
        store.record(charge(30, power = 4.0).with("bmsState" to 13.0))
        val input = only("charge").metrics.getValue("batteryInKwh")
        store.record(charge(45, power = 4.0).with("bmsState" to 13.0))
        assertEquals("active", only("charge").status)
        assertEquals(input, only("charge").metrics.getValue("batteryInKwh"), 1e-9)
        assertTrue(only("charge").metrics.getValue("batteryOutKwh") > 0)
        store.record(charge(60, power = 0.0).with("bmsState" to 2.0))
        assertEquals("active", only("charge").status)
        store.record(charge(75, power = 0.0).with("bmsState" to 2.0))
        assertEquals("complete", only("charge").status)
        assertTrue(only("charge").note.contains("暂停"))
    }

    @Test fun `trip starts above three and includes its fresh preceding anchor`() {
        store.record(trip(0, 100.0, speed = 0.0))
        store.record(trip(15, 100.1, speed = 3.0))
        assertTrue(store.sessions().isEmpty())
        store.record(trip(30, 100.2, speed = 3.1))
        val session = only("trip")
        assertEquals(trip(15).timestamp, session.startedAt)
        assertEquals(0.1, session.metrics.getValue("distanceKm"), 1e-8)
        assertEquals(2, store.points(session.id).size)
    }

    @Test fun `charge confirmation ends trip at first charge point without double integration`() {
        store.record(trip(0))
        store.record(charge(15))
        val tripInputBefore = only("trip").metrics.getValue("batteryInKwh")
        store.record(charge(30))
        val old = only("trip")
        assertEquals("complete", old.status)
        assertEquals(charge(15).timestamp, old.endedAt)
        assertEquals(tripInputBefore, old.metrics.getValue("batteryInKwh"), 1e-9)
        assertEquals(charge(15).timestamp, only("charge").startedAt)
    }

    @Test fun `ten continuous idle minutes ends trip but missing speed resets idle evidence`() {
        store.record(trip(0))
        for (second in 60L..600L step 60) store.record(trip(second, speed = 0.0))
        assertEquals("active", only("trip").status)
        store.record(trip(660, speed = 0.0))
        assertEquals("complete", only("trip").status)
        store.finishOpen("reset")
        store.record(trip(720))
        for (second in 780L..1260L step 60) store.record(trip(second, speed = 0.0))
        store.record(sample(1320, mapOf("soc" to 50.0)))
        store.record(trip(1380, speed = 0.0))
        assertEquals("active", store.sessions("trip").first().status)
    }

    @Test fun `sign crossing splits positive and negative triangular areas`() {
        store.record(trip(0, power = -30.0))
        store.record(trip(60, power = 90.0))
        val m = only("trip").metrics
        assertEquals(0.5625, m.getValue("batteryOutKwh"), 1e-9)
        assertEquals(0.0625, m.getValue("batteryInKwh"), 1e-9)
        assertEquals(0.5, m.getValue("netKwh"), 1e-9)
        val reverse = N9RecordingStore.splitEnergy(90.0, -30.0, 60.0)
        assertEquals(0.5625, reverse.first, 1e-9)
        assertEquals(0.0625, reverse.second, 1e-9)
    }

    @Test fun `each branch has independent energy and coverage`() {
        store.record(trip(0).with("frontPowerKw" to 24.0, "rearPowerKw" to 12.0, "compressorKw" to 2.0))
        store.record(trip(60).with("frontPowerKw" to 48.0, "compressorKw" to 4.0))
        val m = only("trip").metrics
        assertEquals(0.6, m.getValue("frontOutKwh"), 1e-9)
        assertEquals(0.05, m.getValue("compressorKwh"), 1e-9)
        assertEquals(1.0, m.getValue("coverageFront"), 1e-9)
        assertEquals(0.0, m.getValue("coverageRear"), 1e-9)
        assertFalse(m.containsKey("rearOutKwh"))
        assertFalse(m.containsKey("otherKwh"))
    }

    @Test fun `missing channels remain absent while measured zeros remain zero`() {
        val values = mapOf("speed" to 10.0, "mileage" to 0.0, "frontPowerKw" to 0.0, "compressorKw" to 0.0)
        store.record(sample(0, values))
        store.record(sample(60, values))
        var m = only("trip").metrics
        assertFalse(m.containsKey("batteryOutKwh"))
        assertFalse(m.containsKey("batteryInKwh"))
        assertEquals(0.0, m.getValue("distanceKm"), 0.0)
        assertEquals(0.0, m.getValue("frontOutKwh"), 0.0)
        assertEquals(0.0, m.getValue("coverageBattery"), 0.0)
        store.record(sample(120, values + ("batteryPowerKw" to 0.0)))
        store.record(sample(180, values + ("batteryPowerKw" to 0.0)))
        m = only("trip").metrics
        assertEquals(0.0, m.getValue("batteryInKwh"), 0.0)
        assertEquals(1.0 / 3.0, m.getValue("coverageBattery"), 1e-9)
    }

    @Test fun `mileage reset and jump are excluded without inventing speed based distance`() {
        store.record(trip(0, 100.0))
        store.record(trip(60, 99.0))
        assertFalse(only("trip").metrics.containsKey("distanceKm"))
        store.record(trip(120, 99.5))
        store.record(trip(180, 130.0))
        val session = only("trip")
        assertEquals(0.5, session.metrics.getValue("distanceKm"), 1e-9)
        assertEquals(1.0 / 3.0, session.metrics.getValue("coverageDistance"), 1e-9)
        assertTrue(session.note.contains("里程回退或跳变"))
    }

    @Test fun `gap and connection failure interrupt without integrating across them`() {
        store.record(trip(0))
        store.record(trip(60))
        store.record(trip(180))
        val old = only("trip")
        assertEquals("interrupted", old.status)
        assertEquals(trip(60).timestamp, old.endedAt)
        assertEquals(1.0, old.metrics.getValue("batteryOutKwh"), 1e-9)
        store.record(trip(240))
        val resumed = store.sessions("trip").first()
        assertEquals(trip(180).timestamp, resumed.startedAt)
        assertEquals(1.0, resumed.metrics.getValue("batteryOutKwh"), 1e-9)
        store.record(sample(255, emptyMap()).copy(connectionError = "ADB disconnected"))
        assertTrue(store.sessions().all { it.status == "interrupted" })
        assertEquals("ADB disconnected", store.latest()!!.connectionError)
    }

    @Test fun `monotonic reversal and wall clock jump interrupt records`() {
        store.record(trip(0))
        store.record(trip(15))
        store.record(trip(30).copy(monotonicMs = trip(15).monotonicMs))
        assertEquals("interrupted", only("trip").status)
        store.finishOpen("reset")
        store.record(trip(60))
        store.record(trip(75).copy(timestamp = trip(75).timestamp + 6000L))
        assertEquals("interrupted", store.sessions("trip").first().status)
        assertTrue(store.sessions("trip").first().note.contains("系统时间跳变"))
    }

    @Test fun `reconstructed service interrupts open records and resets integration anchor`() {
        store.record(trip(0))
        store.record(trip(15))
        store.close()
        store = N9RecordingStore(context)
        store.finishOpen("进程重启，连续记录中断")
        assertEquals("interrupted", only("trip").status)
        store.record(trip(30))
        val new = store.sessions("trip").first()
        assertEquals(trip(30).timestamp, new.startedAt)
        assertFalse(new.metrics.containsKey("batteryOutKwh"))
        assertEquals(0.0, new.metrics.getValue("durationSeconds"), 0.0)
    }

    @Test fun `capacity estimate requires completed well covered twenty point SOC interval`() {
        store.record(charge(0, 20.0, -60.0))
        store.record(charge(60, 40.0, -60.0))
        store.record(charge(75, 40.0, 0.0).with("bmsState" to 2.0))
        store.record(charge(90, 40.0, 0.0).with("bmsState" to 2.0))
        val session = only("charge")
        assertEquals(5.625, session.metrics.getValue("capacityEstimateKwh"), 1e-9)
        assertTrue(session.note.contains("不代表真实容量"))
        store.finishOpen("reset")
        store.record(charge(120, 20.0, -60.0))
        store.record(charge(180, 40.0, -60.0))
        store.finishOpen("手动停止")
        assertFalse(store.sessions("charge").first().metrics.containsKey("capacityEstimateKwh"))
    }

    @Test fun `low coverage prevents capacity estimate and lifetime counter is never energy`() {
        store.record(charge(0, 20.0).copy(values = charge(0).values - "batteryPowerKw"))
        store.record(charge(15, 40.0).with("totalElecConsumption" to 99999.0))
        store.record(charge(30, 40.0, 0.0).with("bmsState" to 2.0))
        store.record(charge(45, 40.0, 0.0).with("bmsState" to 2.0))
        assertFalse(only("charge").metrics.containsKey("capacityEstimateKwh"))
        assertTrue(only("charge").metrics.getValue("batteryInKwh") < 1.0)
    }

    @Test fun `health extrema use decoded values and missing final SOC is not stale`() {
        store.record(trip(0).with("minCellVoltage" to 3.300, "maxCellVoltage" to 3.307, "minBatTemp" to 20.0, "maxBatTemp" to 25.0))
        store.record(sample(15, mapOf("speed" to 20.0, "minCellVoltage" to 3.301, "maxCellVoltage" to 3.312, "minBatTemp" to 18.0, "maxBatTemp" to 28.0)))
        val m = only("trip").metrics
        assertEquals(11.0, m.getValue("maxCellDeltaMv"), 1e-8)
        assertEquals(18.0, m.getValue("minTempC"), 0.0)
        assertEquals(28.0, m.getValue("maxTempC"), 0.0)
        assertFalse(m.containsKey("endSoc"))
    }

    @Test fun `failed SQL transaction does not advance state or overwrite latest sample`() {
        store.record(trip(0))
        context.openOrCreateDatabase(N9RecordingStore.DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TRIGGER reject_sample BEFORE INSERT ON samples BEGIN SELECT RAISE(ABORT,'test write failure'); END")
        }
        try { store.record(trip(15)); fail("write failure must propagate") } catch (_: android.database.SQLException) { }
        assertEquals(trip(0).timestamp, store.latest()!!.timestamp)
        assertEquals(1, store.points(only("trip").id).size)
        context.openOrCreateDatabase(N9RecordingStore.DATABASE_NAME, Context.MODE_PRIVATE, null).use { it.execSQL("DROP TRIGGER reject_sample") }
        store.record(trip(30))
        assertEquals(0.5, only("trip").metrics.getValue("batteryOutKwh"), 1e-9)
    }

    @Test fun `nonfinite values fail without mutating persisted records`() {
        store.record(trip(0))
        try { store.record(trip(15).with("batteryPowerKw" to Double.NaN)); fail("NaN must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(trip(0).timestamp, store.latest()!!.timestamp)
        assertEquals(1, store.recentSamples().size)
    }

    @Test fun `queries exports and reload retain records but raw only appears in latest`() {
        store.record(trip(0).copy(raw = "first raw should not repeat"))
        store.record(trip(15).copy(raw = "latest\tParcel\n" + "X".repeat(20_000)))
        val session = only("trip")
        assertTrue(store.latest()!!.raw.length <= 16_384)
        assertTrue(store.latest()!!.raw.contains("truncated"))
        assertTrue(store.recentSamples().all { it.raw.isEmpty() })
        assertEquals(listOf(trip(0).timestamp, trip(15).timestamp), store.points(session.id).map { it.timestamp })
        val csv = store.exportCsv()
        assertTrue(csv.startsWith("\"recordType\""))
        assertTrue(csv.contains("\"metadata\""))
        assertTrue(csv.contains("metric.batteryOutKwh"))
        assertTrue(csv.contains("sample.mileage"))
        assertTrue(csv.contains("Required Notice"))
        val text = store.exportText()
        assertTrue(text.contains("latest\tParcel"))
        assertFalse(text.contains("first raw should not repeat"))
        assertTrue(text.contains("缺失字段不代表0"))
        store.close()
        store = N9RecordingStore(context)
        assertEquals(session, only("trip"))
        assertEquals(1, store.recentSamples(1).size)
        assertTrue(store.recentSamples(0).isEmpty())
    }

    @Test fun `sample retention preserves summaries and marks expired curves`() {
        store.record(trip(0))
        val sessionId = only("trip").id
        context.openOrCreateDatabase(N9RecordingStore.DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.beginTransaction()
            try {
                val stmt = db.compileStatement("INSERT INTO samples(wall_ms,mono_ms,values_json) VALUES(1,1,'{}')")
                repeat(N9RecordingStore.MAX_SAMPLES) { stmt.executeInsert() }
                stmt.close()
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        store.record(trip(15))
        assertEquals(N9RecordingStore.MAX_SAMPLES, store.recentSamples(Int.MAX_VALUE).size)
        assertTrue(only("trip").note.contains("曲线可能已压缩/过期"))
        assertEquals(1, store.points(sessionId).size)
    }

    @Test fun `CSV exports every real field definition and associates samples with sessions`() {
        val values = N9Fields.definitions.mapIndexed { index, field -> field.key to index + 0.25 }.toMap()
        store.record(sample(0, values))
        val lines = store.exportCsv().lineSequence().filter { it.isNotEmpty() }.toList()
        fun columns(line: String) = line.removePrefix("\"").removeSuffix("\"").split("\",\"")
        val headers = columns(lines.first())
        val row = columns(lines.single { it.startsWith("\"sample\"") })
        assertEquals(headers.size, row.size)
        for (field in N9Fields.definitions) {
            val position = headers.indexOf("sample.${field.key}")
            assertTrue("missing export field ${field.key}", position >= 0)
            assertEquals(values.getValue(field.key).toString(), row[position])
        }
        assertEquals(only("trip").id.toString(), row[headers.indexOf("sessionIds")])
        assertTrue(headers.contains("sample.voltage12v"))
        assertTrue(headers.contains("sample.batteryRemainKwh"))
        assertTrue(headers.contains("sample.chargingCapacity"))
        assertTrue(headers.contains("sample.bmsMaxChargeKw"))
    }

    @Test fun `session retention keeps the latest two hundred summaries`() {
        repeat(203) { index ->
            store.record(trip(index * 15L))
            store.finishOpen("测试停止")
        }
        val all = store.sessions()
        assertEquals(200, all.size)
        assertTrue(all.zipWithNext().all { (a, b) -> a.id > b.id })
        assertTrue(all.all { it.status == "interrupted" })
    }
}
