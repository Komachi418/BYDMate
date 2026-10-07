package com.bydmate.app.n9

import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AutoserviceClientImpl
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidMap
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.Locale

data class N9BatterySnapshot(
    val capturedAtMillis: Long,
    val soc: Float?,
    val soh: Int?,
    val totalElecConsumption: Float?,
    val minCellMv: Int?,
    val maxCellMv: Int?,
    val diagnostic: String,
) {
    val deltaMv: Int? get() = if (minCellMv != null && maxCellMv != null && maxCellMv >= minCellMv) {
        maxCellMv - minCellMv
    } else null

    val hasReadings: Boolean get() = soc != null || soh != null || totalElecConsumption != null ||
        minCellMv != null || maxCellMv != null
}

/** The V1 on-car read path: one classic ADB connection and five known getters.
 * Every result belongs to this invocation; an unavailable field never reuses an older value.
 * The Activity owns shutdown in its finally block, including when the user closes it. */
class N9BatteryReader(private val adb: AdbOnDeviceClient) {
    suspend fun read(): N9BatterySnapshot {
        val diagnostic = StringBuilder("[classic 5555 / battery]\n")
        diagnostic.appendLine("readStartedAt=${Instant.now()}")
        val connection: Result<Unit> = try {
            adb.connect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val connected = try {
            adb.isConnected()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostic.appendLine("classic5555 connected check failed=${failure.javaClass.simpleName}")
            false
        }
        val connectionFailure = try {
            adb.lastConnectFailure()?.toString() ?: "none"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.javaClass.simpleName
        }
        diagnostic.appendLine("classic5555 handshake=${connection.isSuccess} connected=$connected failure=$connectionFailure")
        if (connection.isFailure || !connected) {
            diagnostic.appendLine("autoservice probe=NOT_RUN; all five battery fields=unavailable (classic ADB not connected)")
            connection.exceptionOrNull()?.let {
                diagnostic.appendLine("connection error=${it.javaClass.simpleName}: ${it.message}")
            }
            val time = System.currentTimeMillis()
            diagnostic.appendLine("readFinishedAt=${Instant.ofEpochMilli(time)}")
            return N9BatterySnapshot(time, null, null, null, null, null, boundedN9Diagnostic(diagnostic.toString()))
        }

        val recordingAdb = object : AdbOnDeviceClient by adb {
            override suspend fun exec(cmd: String): String? {
                diagnostic.appendLine("\$ $cmd")
                return try {
                    adb.exec(cmd).also { raw ->
                        diagnostic.appendLine(raw?.let { boundedN9Diagnostic(it, 2048) } ?: "<no ADB reply>")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    diagnostic.appendLine("<ADB read failed: ${failure.javaClass.simpleName}: ${failure.message}>")
                    throw failure
                }
            }
        }
        val autoservice = AutoserviceClientImpl(recordingAdb)
        suspend fun readInt(field: String, range: IntRange): Int? = fieldRead(field, diagnostic) {
            val entry = FidMap.byField.getValue(field)
            check(entry.transact == 5) { "Expected existing GET_INT address" }
            val value = autoservice.getInt(entry.device, entry.fid)
            if (value != null && value !in range) diagnostic.appendLine("$field rejected out-of-range value=$value")
            value?.takeIf { it in range }
        }
        suspend fun readFloat(field: String, valid: (Float) -> Boolean): Float? = fieldRead(field, diagnostic) {
            val entry = FidMap.byField.getValue(field)
            check(entry.transact == 7) { "Expected existing GET_FLOAT address" }
            val value = autoservice.getFloat(entry.device, entry.fid)
            // A raw integer error sentinel can otherwise look like a tiny positive float.
            val accepted = value != null && value.isFinite() &&
                SentinelDecoder.decodeInt(value.toRawBits()) != null && valid(value)
            if (value != null && !accepted) diagnostic.appendLine("$field rejected sentinel/out-of-range value=$value")
            value?.takeIf { accepted }
        }

        val soh = readInt("soh", 0..100)
        val soc = readFloat("soc") { it in 0f..100f }
        val total = readFloat("totalElecConsumption") { it >= 0f }
        val min = readInt("minCellVoltage", 500..6000)
        val max = readInt("maxCellVoltage", 500..6000)
        val validCount = listOf(soh, soc, total, min, max).count { it != null }
        diagnostic.appendLine("autoservice probe=${when (validCount) {
            5 -> "VALID_READING"
            0 -> "NO_VALID_READING"
            else -> "PARTIAL"
        }} valid fields=$validCount/5")
        if (min != null && max != null && max < min) diagnostic.appendLine("cell delta=unavailable (max below min)")
        diagnostic.appendLine("Known FidMap only; cell readings are integer mV; raw Parcel whitespace preserved above.")
        val connectedAfter = try {
            adb.isConnected().toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "unavailable (${failure.javaClass.simpleName})"
        }
        diagnostic.appendLine("classic5555 connected after reads=$connectedAfter")
        val time = System.currentTimeMillis()
        diagnostic.appendLine("readFinishedAt=${Instant.ofEpochMilli(time)}")
        return N9BatterySnapshot(time, soc, soh, total, min, max, boundedN9Diagnostic(diagnostic.toString()))
    }

    private suspend fun <T> fieldRead(field: String, diagnostic: StringBuilder, read: suspend () -> T?): T? {
        val value = try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostic.appendLine("$field read error=${failure.javaClass.simpleName}: ${failure.message}")
            null
        }
        diagnostic.appendLine("$field=${value ?: "unavailable (no valid reply; see raw/error)"}")
        return value
    }
}

internal const val N9_BATTERY_DIAGNOSTIC_LIMIT = 16_384

internal fun boundedN9Diagnostic(text: String, limit: Int = N9_BATTERY_DIAGNOSTIC_LIMIT): String {
    val marker = "\n<diagnostic truncated at $limit characters>"
    return if (text.length <= limit) text else text.take(limit - marker.length) + marker
}

/** Pure export helpers; these perform no reads or writes. */
fun N9BatterySnapshot.toBatteryReportText(): String = buildString {
    appendLine("time=${Instant.ofEpochMilli(capturedAtMillis)} capturedAtMillis=$capturedAtMillis")
    appendLine("SOC=${soc?.let { "$it %" } ?: "不可用"}")
    appendLine("SoH=${soh?.let { "$it %" } ?: "不可用"}")
    appendLine("累计电量=${totalElecConsumption?.let { "$it kWh" } ?: "不可用"}")
    appendLine("最低电芯电压=${minCellMv?.let { "${String.format(Locale.ROOT, "%.3f", it / 1000.0)} V ($it mV)" } ?: "不可用"}")
    appendLine("最高电芯电压=${maxCellMv?.let { "${String.format(Locale.ROOT, "%.3f", it / 1000.0)} V ($it mV)" } ?: "不可用"}")
    appendLine("电芯压差=${deltaMv?.let { "$it mV" } ?: "不可用"}")
    appendLine("hasReadings=$hasReadings")
    appendLine("[诊断 / 原始 Parcel]")
    append(diagnostic)
}

fun n9BatteryHistoryText(snapshots: List<N9BatterySnapshot>): String = buildString {
    appendLine("BYDMate N9 只读电池记录")
    appendLine("记录按手动读取的先后顺序列出，包括读取失败；字段不可用时未沿用上次值。")
    appendLine("这些是车机上报的顺序读数，不用于单独判断电池健康或校准完成。")
    snapshots.forEachIndexed { index, snapshot ->
        appendLine("\n--- ${index + 1}/${snapshots.size} ---")
        appendLine(snapshot.toBatteryReportText())
    }
}
