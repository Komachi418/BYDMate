package com.bydmate.app.n9

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import com.bydmate.app.BuildConfig
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

data class N9Session(
    val id: Long,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val metrics: Map<String, Double>,
    val note: String,
)

/** Private, synchronous store. Call on IO; a failed transaction always propagates to the caller. */
class N9RecordingStore(context: Context) {
    private val helper = Database(context.applicationContext)

    fun record(sample: N9Sample) = transaction { db ->
        require(sample.values.values.all { it.isFinite() }) { "Non-finite sample value" }
        val state = state(db)
        val previous = state.previousId?.let { readSample(db, it) }
        val id = db.insertOrThrow("samples", null, ContentValues().apply {
            put("wall_ms", sample.timestamp)
            put("mono_ms", sample.monotonicMs)
            put("values_json", json(sample.values))
            put("error", sample.connectionError)
        })
        meta(db, "latest_raw", boundedRaw(sample.raw))
        val failure = when {
            sample.connectionError != null -> "读取中断：${sample.connectionError.take(200)}"
            sample.values.isEmpty() -> "读取中断：没有有效数据"
            state.chargeId != null && sample.values["chargeGunState"] == 5.0 -> "检测到VTOL外放状态，充电记录中断"
            previous == null -> null
            sample.monotonicMs <= previous.monotonicMs -> "单调时钟倒退或重复，连续记录中断"
            sample.monotonicMs - previous.monotonicMs > MAX_GAP_MS -> "采样间隔超过90秒，连续记录中断"
            abs((sample.timestamp - previous.timestamp).toDouble() -
                (sample.monotonicMs - previous.monotonicMs).toDouble()) > CLOCK_SKEW_MS -> "系统时间跳变，连续记录中断"
            else -> null
        }
        if (failure != null) {
            interruptAll(db, failure)
            // A healthy reading after a gap becomes a fresh anchor, never a bridge.
            val replacement = State(previousId = id.takeIf { sample.connectionError == null && sample.values.isNotEmpty() })
            saveState(db, replacement)
            prune(db)
            return@transaction
        }

        val chargingEvidence = sample.values["bmsState"] == 1.0 && plugged(sample)
        var charge = state.chargeId?.let { sessionAcc(db, it) }
        var trip = state.tripId?.let { sessionAcc(db, it) }
        val hadCharge = charge != null
        if (charge == null && chargingEvidence) {
            val candidate = state.chargeCandidateId?.let { readSample(db, it) }
            if (candidate != null && previous != null && state.chargeCandidateId == state.previousId) {
                // The trip already contains this first confirmed charge point. End it
                // there; do not double-count the confirmation interval in both sessions.
                trip?.let { complete(db, it, "充电开始，行程结束") }
                trip = null
                state.tripId = null
                state.stillSince = null
                charge = createSession(db, "charge", candidate, state.chargeCandidateId!!)
                state.chargeId = charge.id
                state.chargeGunSeen = candidate.values["chargeGunState"] in listOf(2.0, 3.0, 4.0)
                state.chargeCandidateId = null
            } else {
                state.chargeCandidateId = id
            }
        } else if (!chargingEvidence) {
            state.chargeCandidateId = null
        }

        if (charge != null) {
            previous?.let { accumulate(charge, it, sample) }
            observe(charge, sample)
            link(db, charge.id, id)
            state.chargeGunSeen = state.chargeGunSeen || sample.values["chargeGunState"] in listOf(2.0, 3.0, 4.0)
            state.finishCount = if (sample.values["bmsState"] == 2.0) state.finishCount + 1 else 0
            state.disconnectCount = if (explicitlyUnplugged(sample, state.chargeGunSeen)) state.disconnectCount + 1 else 0
            if (sample.values["bmsState"] == 13.0) addNote(charge, "包含BMS暂停阶段；保留同一充电记录")
            if (state.finishCount >= 2 || state.disconnectCount >= 2) {
                complete(db, charge, if (state.finishCount >= 2) "BMS连续两次上报完成" else "连续两次确认拔枪")
                state.chargeId = null
                state.finishCount = 0
                state.disconnectCount = 0
            } else save(db, charge)
        } else {
            state.finishCount = 0
            state.disconnectCount = 0
        }

        if (state.chargeId == null) {
            val speed = sample.values["speed"]
            if (trip == null && speed != null && speed > 3.0) {
                val anchor = previous.takeUnless { hadCharge || charge != null } ?: sample
                val anchorId = if (anchor === sample) id else state.previousId!!
                trip = createSession(db, "trip", anchor, anchorId)
                state.tripId = trip.id
            }
            if (trip != null) {
                previous?.let { accumulate(trip, it, sample) }
                observe(trip, sample)
                link(db, trip.id, id)
                state.stillSince = if (speed != null && speed <= 1.0 && speed >= 0.0) {
                    state.stillSince ?: sample.monotonicMs
                } else null
                if (state.stillSince?.let { sample.monotonicMs - it >= IDLE_END_MS } == true) {
                    complete(db, trip, "连续静止10分钟，行程结束")
                    state.tripId = null
                    state.stillSince = null
                } else save(db, trip)
            }
        }
        state.previousId = id
        saveState(db, state)
        prune(db)
    }

    fun latest(): N9Sample? = read { db ->
        db.rawQuery("SELECT * FROM samples ORDER BY id DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.readSample(meta(db, "latest_raw") ?: "") else null
        }
    }

    /** Last N acquisitions, in acquisition order even if the wall clock moved. */
    fun recentSamples(limit: Int = 120): List<N9Sample> = read { db ->
        db.rawQuery("SELECT * FROM (SELECT * FROM samples ORDER BY id DESC LIMIT ?) ORDER BY id", arrayOf(limit.coerceIn(0, MAX_SAMPLES).toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readSample()) } }
    }

    fun sessions(kind: String? = null): List<N9Session> = read { db -> sessions(db, kind) }

    fun points(sessionId: Long): List<N9Sample> = read { db ->
        db.rawQuery("SELECT s.* FROM samples s JOIN session_samples p ON p.sample_id=s.id WHERE p.session_id=? ORDER BY s.id", arrayOf(sessionId.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readSample()) } }
    }

    fun finishOpen(reason: String) = transaction { db ->
        interruptAll(db, reason)
        saveState(db, State())
    }

    fun exportCsv(): String = read { db ->
        val headers = listOf("recordType", "id", "sessionIds", "kind", "timestamp", "monotonicMs", "startedAt", "endedAt", "status", "note") +
            SAMPLE_FIELDS.map { "sample.$it" } + METRIC_FIELDS.map { "metric.$it" }
        buildString {
            appendLine(headers.joinToString(",", transform = ::csv))
            fun row(prefix: List<String>, values: Map<String, Double> = emptyMap(), metrics: Map<String, Double> = emptyMap()) {
                appendLine((prefix + SAMPLE_FIELDS.map { values[it]?.toString() ?: "" } +
                    METRIC_FIELDS.map { metrics[it]?.toString() ?: "" }).joinToString(",", transform = ::csv))
            }
            row(listOf("metadata", "", "", "", "", "", "", "", "", identity() + " | " + NOTICE + " | " + RETENTION_NOTE))
            sessions(db, null).forEach { s -> row(listOf("session", s.id.toString(), s.id.toString(), s.kind, "", "", s.startedAt.toString(), s.endedAt?.toString() ?: "", s.status, s.note), metrics = s.metrics) }
            db.rawQuery("SELECT s.*, COALESCE((SELECT group_concat(session_id,';') FROM session_samples p WHERE p.sample_id=s.id),'') AS session_ids FROM samples s ORDER BY s.id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val s = cursor.readSample()
                    row(listOf("sample", cursor.long("id").toString(), cursor.string("session_ids"), "", s.timestamp.toString(), s.monotonicMs.toString(), "", "", "", s.connectionError ?: ""), values = s.values)
                }
            }
        }
    }

    fun exportText(): String = read { db -> buildString {
        appendLine("BYDMate N9 只读电池与行程记录")
        appendLine(identity())
        appendLine(NOTICE)
        appendLine(RETENTION_NOTE)
        appendLine(BASE_NOTE)
        sessions(db, null).forEach { s ->
            appendLine("\nsession=${s.id} kind=${s.kind} status=${s.status} start=${s.startedAt} end=${s.endedAt ?: "?"}")
            appendLine(s.metrics.entries.joinToString(" ") { "${it.key}=${it.value}" })
            appendLine(s.note)
        }
        appendLine("\n样本（采集顺序；缺失字段表示无有效读数）")
        db.rawQuery("SELECT * FROM samples ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val s = cursor.readSample()
                appendLine("sample=${cursor.long("id")} time=${s.timestamp} monotonic=${s.monotonicMs} values=${json(s.values)} error=${s.connectionError ?: ""}")
            }
        }
        appendLine("\n最新原始读取（最多16 KiB字符）")
        appendLine(meta(db, "latest_raw") ?: "")
    } }

    fun close() = synchronized(LOCK) { helper.close() }

    private fun createSession(db: SQLiteDatabase, kind: String, start: N9Sample, sampleId: Long): Acc {
        val acc = Acc(0L, kind, start.timestamp, start.timestamp, start.monotonicMs)
        start.values["soc"]?.let { acc.metrics["startSoc"] = it }
        start.values["soh"]?.let { acc.metrics["startSoh"] = it }
        observe(acc, start)
        acc.id = db.insertOrThrow("sessions", null, content(acc))
        link(db, acc.id, sampleId)
        return acc
    }

    private fun observe(acc: Acc, value: N9Sample) {
        for ((from, to) in listOf("soc" to "endSoc", "soh" to "endSoh")) {
            value.values[from]?.let { acc.metrics[to] = it } ?: acc.metrics.remove(to)
        }
        val low = value.values["minCellVoltage"]
        val high = value.values["maxCellVoltage"]
        if (low != null && high != null && high >= low) {
            val delta = (high - low) * 1000.0
            acc.metrics["maxCellDeltaMv"] = max(acc.metrics["maxCellDeltaMv"] ?: delta, delta)
        }
        value.values["minBatTemp"]?.let { acc.metrics["minTempC"] = minOf(acc.metrics["minTempC"] ?: it, it) }
        value.values["maxBatTemp"]?.let { acc.metrics["maxTempC"] = maxOf(acc.metrics["maxTempC"] ?: it, it) }
        if (acc.kind == "charge") value.values["batteryPowerKw"]?.let {
            val input = max(0.0, -it)
            acc.metrics["peakChargeKw"] = max(acc.metrics["peakChargeKw"] ?: input, input)
        }
        acc.lastAt = value.timestamp
        acc.lastMono = value.monotonicMs
    }

    private fun accumulate(acc: Acc, previous: N9Sample, current: N9Sample) {
        // A session just created at the current point has no preceding interval.
        if (acc.lastMono != previous.monotonicMs) return
        val ms = current.monotonicMs - previous.monotonicMs
        if (ms !in 1..MAX_GAP_MS) return
        val seconds = ms / 1000.0
        acc.duration += seconds
        fun integrate(field: String, coverage: String, out: String, input: String? = null, positiveOnly: Boolean = false) {
            val a = previous.values[field] ?: return
            val b = current.values[field] ?: return
            if (positiveOnly && (a < 0.0 || b < 0.0)) return
            val (outKwh, inKwh) = splitEnergy(a, b, seconds)
            acc.metrics[out] = (acc.metrics[out] ?: 0.0) + outKwh
            if (input != null) acc.metrics[input] = (acc.metrics[input] ?: 0.0) + inKwh
            acc.covered[coverage] = (acc.covered[coverage] ?: 0.0) + seconds
        }
        integrate("batteryPowerKw", "Battery", "batteryOutKwh", "batteryInKwh")
        if ("batteryOutKwh" in acc.metrics) acc.metrics["netKwh"] = acc.metrics.getValue("batteryOutKwh") - acc.metrics.getValue("batteryInKwh")
        integrate("frontPowerKw", "Front", "frontOutKwh")
        integrate("rearPowerKw", "Rear", "rearOutKwh")
        integrate("compressorKw", "Compressor", "compressorKwh", positiveOnly = true)
        val a = previous.values["mileage"]
        val b = current.values["mileage"]
        if (a != null && b != null) {
            val delta = b - a
            if (a >= 0 && b >= 0 && delta >= 0 && delta <= 0.1 + seconds * 250.0 / 3600.0) {
                acc.metrics["distanceKm"] = (acc.metrics["distanceKm"] ?: 0.0) + delta
                acc.covered["Distance"] = (acc.covered["Distance"] ?: 0.0) + seconds
            } else addNote(acc, "里程回退或跳变区间已跳过，未用速度积分替代")
        }
    }

    private fun complete(db: SQLiteDatabase, acc: Acc, reason: String) {
        acc.status = "complete"
        addNote(acc, reason)
        val delta = acc.metrics["endSoc"]?.let { end -> acc.metrics["startSoc"]?.let { end - it } }
        val netInput = acc.metrics["netKwh"]?.let { -it }
        if (acc.kind == "charge" && delta != null && delta >= 20.0 && netInput != null && netInput > 0.0 && coverage(acc, "Battery") >= 0.95) {
            acc.metrics["capacityEstimateKwh"] = netInput * 100.0 / delta
            addNote(acc, "容量为本区间SOC与净入电折算，仅供参考，不代表真实容量或健康鉴定")
        }
        save(db, acc)
    }

    private fun interruptAll(db: SQLiteDatabase, reason: String) {
        val ids = db.rawQuery("SELECT id FROM sessions WHERE status='active'", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        ids.forEach { id -> sessionAcc(db, id)?.let {
            it.status = "interrupted"
            it.metrics.remove("capacityEstimateKwh")
            addNote(it, reason)
            save(db, it)
        } }
    }

    private fun save(db: SQLiteDatabase, acc: Acc) { check(db.update("sessions", content(acc), "id=?", arrayOf(acc.id.toString())) == 1) }

    private fun content(acc: Acc) = ContentValues().apply {
        put("kind", acc.kind); put("started_at", acc.startedAt)
        if (acc.status == "active") putNull("ended_at") else put("ended_at", acc.lastAt)
        put("status", acc.status); put("note", acc.note)
        val metrics = acc.metrics.toMutableMap().apply {
            put("durationSeconds", acc.duration)
            COVERAGE.forEach { put("coverage$it", coverage(acc, it)) }
        }
        put("metrics_json", json(metrics))
        put("acc_json", JSONObject().put("lastAt", acc.lastAt).put("lastMono", acc.lastMono)
            .put("duration", acc.duration).put("covered", JSONObject(acc.covered as Map<*, *>)).toString())
    }

    private fun sessionAcc(db: SQLiteDatabase, id: Long): Acc? = db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) null else {
            val a = JSONObject(c.string("acc_json"))
            Acc(id, c.string("kind"), c.long("started_at"), a.getLong("lastAt"), a.getLong("lastMono"),
                a.getDouble("duration"), map(c.string("metrics_json")).toMutableMap(), map(a.getJSONObject("covered").toString()).toMutableMap(), c.string("status"), c.string("note"))
        }
    }

    private fun sessions(db: SQLiteDatabase, kind: String?): List<N9Session> {
        require(kind == null || kind == "charge" || kind == "trip")
        val query = "SELECT * FROM sessions" + (if (kind == null) "" else " WHERE kind=?") + " ORDER BY id DESC"
        return db.rawQuery(query, kind?.let { arrayOf(it) }).use { c -> buildList {
            while (c.moveToNext()) add(N9Session(c.long("id"), c.string("kind"), c.long("started_at"),
                c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getLong(it) }, c.string("status"), map(c.string("metrics_json")), c.string("note")))
        } }
    }

    private fun readSample(db: SQLiteDatabase, id: Long): N9Sample? = db.rawQuery("SELECT * FROM samples WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.readSample() else null }
    private fun Cursor.readSample(raw: String = "") = N9Sample(long("wall_ms"), long("mono_ms"), map(string("values_json")), raw, getColumnIndexOrThrow("error").let { if (isNull(it)) null else getString(it) })
    private fun link(db: SQLiteDatabase, sessionId: Long, sampleId: Long) { db.execSQL("INSERT OR IGNORE INTO session_samples(session_id,sample_id) VALUES(?,?)", arrayOf(sessionId, sampleId)) }
    private fun meta(db: SQLiteDatabase, key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun meta(db: SQLiteDatabase, key: String, value: String) { db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) } }
    private fun state(db: SQLiteDatabase): State {
        val j = JSONObject(meta(db, "state") ?: "{}")
        fun optional(key: String) = if (j.has(key) && !j.isNull(key)) j.getLong(key) else null
        return State(optional("previousId"), optional("chargeCandidateId"), optional("chargeId"), optional("tripId"), j.optInt("finishCount"), j.optInt("disconnectCount"), optional("stillSince"), j.optBoolean("chargeGunSeen"))
    }
    private fun saveState(db: SQLiteDatabase, state: State) { meta(db, "state", JSONObject().apply {
        put("previousId", state.previousId); put("chargeCandidateId", state.chargeCandidateId)
        put("chargeId", state.chargeId); put("tripId", state.tripId)
        put("finishCount", state.finishCount); put("disconnectCount", state.disconnectCount); put("stillSince", state.stillSince)
        put("chargeGunSeen", state.chargeGunSeen)
    }.toString()) }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = db.rawQuery("SELECT id FROM samples ORDER BY id DESC LIMIT 1 OFFSET ${MAX_SAMPLES - 1}", null).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (cutoff != null) {
            db.execSQL("UPDATE sessions SET note=note || ? WHERE instr(note,?)=0 AND id IN (SELECT session_id FROM session_samples WHERE sample_id < ?)", arrayOf("；$CURVE_EXPIRED", CURVE_EXPIRED, cutoff))
            db.delete("samples", "id < ?", arrayOf(cutoff.toString()))
        }
        db.execSQL("DELETE FROM sessions WHERE status != 'active' AND id NOT IN (SELECT id FROM sessions ORDER BY id DESC LIMIT $MAX_SESSIONS)")
    }

    private fun <T> read(block: (SQLiteDatabase) -> T): T = synchronized(LOCK) { block(helper.readableDatabase) }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T = synchronized(LOCK) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }

    private data class State(var previousId: Long? = null, var chargeCandidateId: Long? = null,
        var chargeId: Long? = null, var tripId: Long? = null, var finishCount: Int = 0,
        var disconnectCount: Int = 0, var stillSince: Long? = null, var chargeGunSeen: Boolean = false)
    private data class Acc(var id: Long, val kind: String, val startedAt: Long, var lastAt: Long,
        var lastMono: Long, var duration: Double = 0.0, val metrics: MutableMap<String, Double> = mutableMapOf(),
        val covered: MutableMap<String, Double> = mutableMapOf(), var status: String = "active", var note: String = BASE_NOTE)

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,wall_ms INTEGER NOT NULL,mono_ms INTEGER NOT NULL,values_json TEXT NOT NULL,error TEXT)")
            db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER,status TEXT NOT NULL,metrics_json TEXT NOT NULL,note TEXT NOT NULL,acc_json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE session_samples(session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,sample_id INTEGER NOT NULL REFERENCES samples(id) ON DELETE CASCADE,PRIMARY KEY(session_id,sample_id))")
            db.execSQL("CREATE INDEX sample_sessions ON session_samples(sample_id)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported recording database upgrade $oldVersion->$newVersion") }
    }

    companion object {
        internal const val DATABASE_NAME = "n9-recording.db"
        internal const val MAX_SAMPLES = 30_000
        internal const val MAX_SESSIONS = 200
        private const val MAX_GAP_MS = 90_000L
        private const val CLOCK_SKEW_MS = 5_000.0
        private const val IDLE_END_MS = 600_000L
        private const val CURVE_EXPIRED = "曲线可能已压缩/过期"
        private const val BASE_NOTE = "接口映射估算待N9验证；电池回充来源未分；前/后电机和压缩机为独立分项，不相加推算其他损耗"
        private const val RETENTION_NOTE = "保留最新30000个采样点及200条会话摘要；旧曲线可能已过期；缺失字段不代表0"
        private const val NOTICE = "PolyForm Noncommercial 1.0.0 | Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)"
        private val LOCK = Any()
        private val COVERAGE = listOf("Battery", "Distance", "Front", "Rear", "Compressor")
        private val SAMPLE_FIELDS = N9Fields.definitions.map { it.key }
        private val METRIC_FIELDS = listOf("startSoc", "endSoc", "startSoh", "endSoh", "distanceKm", "batteryOutKwh", "batteryInKwh", "netKwh", "frontOutKwh", "rearOutKwh", "compressorKwh", "durationSeconds", "coverageBattery", "coverageDistance", "coverageFront", "coverageRear", "coverageCompressor", "peakChargeKw", "maxCellDeltaMv", "minTempC", "maxTempC", "capacityEstimateKwh")
        private fun plugged(s: N9Sample) = s.values["chargeGunState"] != 5.0 && (
            s.values["chargerConnectState"] == 1.0 || s.values["chargeConnectIndicator"] == 1.0 ||
                s.values["chargeGunState"]?.let { it in 2.0..4.0 && it % 1.0 == 0.0 } == true)
        private fun explicitlyUnplugged(s: N9Sample, gunSeen: Boolean) =
            s.values["chargeGunState"] == 1.0 && !plugged(s) &&
                (gunSeen || s.values["chargerConnectState"] == 0.0 || s.values["chargeConnectIndicator"] == 2.0)
        private fun addNote(acc: Acc, note: String) { if (!acc.note.contains(note)) acc.note += "；" + note }
        private fun coverage(acc: Acc, key: String) = if (acc.duration > 0) ((acc.covered[key] ?: 0.0) / acc.duration).coerceIn(0.0, 1.0) else 0.0
        private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
        private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
        private fun json(values: Map<String, Double>) = JSONObject(values as Map<*, *>).toString()
        private fun map(text: String): Map<String, Double> = JSONObject(text).let { j -> buildMap { j.keys().forEach { key -> put(key, j.getDouble(key)) } } }
        private fun boundedRaw(raw: String) = if (raw.length <= 16_384) raw else raw.take(16_350) + "\n[raw truncated]"
        private fun identity() = "package=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} userId=${Process.myUid() / 100000} readOnly=true"
        private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

        /** Integrate the positive/negative parts separately, splitting a linear zero crossing. */
        internal fun splitEnergy(a: Double, b: Double, seconds: Double): Pair<Double, Double> {
            if (a >= 0.0 && b >= 0.0) return (a + b) * 0.5 * seconds / 3600.0 to 0.0
            if (a <= 0.0 && b <= 0.0) return 0.0 to -(a + b) * 0.5 * seconds / 3600.0
            val first = seconds * abs(a) / (abs(a) + abs(b))
            val firstEnergy = abs(a) * first * 0.5 / 3600.0
            val secondEnergy = abs(b) * (seconds - first) * 0.5 / 3600.0
            return if (a > 0.0) firstEnergy to secondEnergy else secondEnergy to firstEnergy
        }
    }
}
