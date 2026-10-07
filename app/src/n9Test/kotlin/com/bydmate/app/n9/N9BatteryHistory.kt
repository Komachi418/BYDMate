package com.bydmate.app.n9

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** App-private history in acquisition order (oldest first), including failed refreshes.
 * AtomicFile protects the previous complete file if a write is interrupted. */
class N9BatteryHistory(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "n9-battery-history.json"))

    fun load(): List<N9BatterySnapshot> = synchronized(LOCK) { readFile() }

    fun append(snapshot: N9BatterySnapshot): Boolean = synchronized(LOCK) {
        var stream: FileOutputStream? = null
        try {
            val snapshots = (readFile() + snapshot).takeLast(MAX_RECORDS)
            val entries = JSONArray()
            snapshots.forEach { entries.put(it.toJson()) }
            val text = JSONObject().put("version", 1).put("snapshots", entries).toString()
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES)
            val output = file.startWrite()
            stream = output
            output.write(bytes)
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { file.failWrite(it) } }
            false
        }
    }

    private fun readFile(): List<N9BatterySnapshot> = try {
        val text = file.openRead().use { input ->
            require(input.channel.size() <= MAX_FILE_BYTES)
            input.bufferedReader(Charsets.UTF_8).readText()
        }
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val entries = root.getJSONArray("snapshots")
        (maxOf(0, entries.length() - MAX_RECORDS) until entries.length()).map { index ->
            entries.getJSONObject(index).toSnapshot()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun N9BatterySnapshot.toJson() = JSONObject().apply {
        put("capturedAtMillis", capturedAtMillis)
        put("soc", soc ?: JSONObject.NULL)
        put("soh", soh ?: JSONObject.NULL)
        put("totalElecConsumption", totalElecConsumption ?: JSONObject.NULL)
        put("minCellMv", minCellMv ?: JSONObject.NULL)
        put("maxCellMv", maxCellMv ?: JSONObject.NULL)
        put("diagnostic", boundedN9Diagnostic(diagnostic))
    }

    private fun JSONObject.toSnapshot(): N9BatterySnapshot {
        fun integer(key: String, range: IntRange): Int? {
            val value = opt(key) as? Number ?: return null
            val whole = value.toInt()
            return whole.takeIf { value.toDouble() == it.toDouble() && it in range }
        }
        fun floating(key: String, valid: (Float) -> Boolean): Float? =
            (opt(key) as? Number)?.toFloat()?.takeIf { it.isFinite() && valid(it) }
        return N9BatterySnapshot(
            capturedAtMillis = getLong("capturedAtMillis"),
            soc = floating("soc") { it in 0f..100f },
            soh = integer("soh", 0..100),
            totalElecConsumption = floating("totalElecConsumption") { it >= 0f },
            minCellMv = integer("minCellMv", 500..6000),
            maxCellMv = integer("maxCellMv", 500..6000),
            diagnostic = boundedN9Diagnostic(getString("diagnostic")),
        )
    }

    private companion object {
        const val MAX_RECORDS = 20
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        val LOCK = Any()
    }
}
