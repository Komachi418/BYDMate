package com.bydmate.app.n9

import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AutoserviceClientImpl
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidEntry
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CancellationException
import java.util.Locale

data class N9BatteryReading(
    val value: Double? = null,
    val raw: String,
    val status: Int? = null,
    val error: String? = null,
    val startedAtMs: Long = 0,
    val finishedAtMs: Long = 0,
) {
    val valid: Boolean get() = value != null && value.isFinite() && error == null
}

data class N9BatteryPair(val field: FidEntry, val direct: N9BatteryReading, val helper: N9BatteryReading) {
    /** Sequential observations, not a claim that a transport caused a change. */
    val observation: String get() = when {
        !direct.valid || !helper.valid -> "UNAVAILABLE"
        direct.value == helper.value -> "SAME"
        else -> "DIFFERENT"
    }
}

data class N9CellRange(val minV: Double?, val maxV: Double?, val deltaMv: Double?, val error: String?)

data class N9BatteryComparisonResult(val readings: List<N9BatteryPair>) {
    fun cellRange(helper: Boolean): N9CellRange {
        fun value(field: String): Double? = readings.firstOrNull { it.field.field == field }
            ?.let { if (helper) it.helper else it.direct }?.takeIf { it.valid }?.value
        val min = value("minCellVoltage")
        val max = value("maxCellVoltage")
        return when {
            min == null || max == null -> N9CellRange(min, max, null, "MISSING_VALID_CELL_READING")
            max < min -> N9CellRange(min, max, null, "MAX_BELOW_MIN")
            else -> N9CellRange(min, max, (max - min) * 1000.0, null)
        }
    }

    fun toReportText(): String = buildString {
        appendLine("[battery direct / Helper comparison]")
        appendLine("每项依次读取 direct → Helper；只使用已知 FID 的 GET_INT/GET_FLOAT，不扫描接口。")
        appendLine("SAME/DIFFERENT 仅描述这两次读数；顺序读取不保证同一时刻，不据此归因或判断电池健康。")
        appendLine("UNAVAILABLE 表示至少一路没有有效读数，不计为相同。时间为每次读取开始/结束的 Unix 毫秒。")
        readings.forEach { pair ->
            val entry = pair.field
            appendLine("${entry.field}: ${pair.observation}; dev=${entry.device} fid=${entry.fid} tx=${entry.transact}")
            for ((path, reading) in listOf("direct" to pair.direct, "Helper" to pair.helper)) {
                val value = reading.takeIf { it.valid }?.value?.let { "${n9Number(it)} ${n9Unit(entry.field)}" }
                    ?: "unavailable"
                appendLine("  $path=$value; status=${reading.status ?: "unavailable"}; error=${reading.error ?: "none"}")
                appendLine("  $path readAtMs=${reading.startedAtMs}..${reading.finishedAtMs}")
                appendLine("  $path raw (original whitespace preserved):")
                appendLine(reading.raw)
            }
        }
        for (useHelper in listOf(false, true)) {
            val path = if (useHelper) "Helper" else "direct"
            val cells = cellRange(useHelper)
            appendLine("$path min cell=${cells.minV?.let(::n9Number) ?: "unavailable"} V; " +
                "max cell=${cells.maxV?.let(::n9Number) ?: "unavailable"} V; " +
                "cell delta=${cells.deltaMv?.let(::n9Number) ?: "unavailable"} mV; error=${cells.error ?: "none"}")
        }
        appendLine("valid pairs=${readings.count { it.direct.valid && it.helper.valid }}/${readings.size}; " +
            "same=${readings.count { it.observation == "SAME" }}; different=${readings.count { it.observation == "DIFFERENT" }}")
    }
}

/** One bounded, explicit check. Single-item batches preserve the Helper's status word,
 * unlike HelperClient.read(), which collapses nonzero statuses to null. */
class N9BatteryComparison(
    private val readDirect: suspend (FidEntry) -> N9BatteryReading,
    private val readHelper: suspend (FidEntry) -> N9BatteryReading,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(adb: AdbOnDeviceClient, helper: HelperClient) : this(directReader(adb), helperReader(helper))

    suspend fun read(): N9BatteryComparisonResult {
        val readings = mutableListOf<N9BatteryPair>()
        for (name in FIELD_NAMES) {
            val entry = FidMap.byField.getValue(name)
            check(entry.transact == 5 || entry.transact == 7) { "N9 battery comparison requires a getter" }
            val direct = capture { readDirect(entry) }
            val helper = capture { readHelper(entry) }
            readings += N9BatteryPair(entry, direct, helper)
        }
        return N9BatteryComparisonResult(readings)
    }

    private suspend fun capture(read: suspend () -> N9BatteryReading): N9BatteryReading {
        val start = nowMs()
        val reading = try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            N9BatteryReading(raw = "<no completed reply>", error = "READ_EXCEPTION:${failure.javaClass.simpleName}")
        }
        return reading.copy(startedAtMs = start, finishedAtMs = nowMs())
    }

    companion object {
        private val FIELD_NAMES = listOf("soh", "soc", "totalElecConsumption", "minCellVoltage", "maxCellVoltage")

        private fun directReader(adb: AdbOnDeviceClient): suspend (FidEntry) -> N9BatteryReading {
            return { entry ->
                var response: String? = null
                val recording = object : AdbOnDeviceClient by adb {
                    override suspend fun exec(cmd: String): String? = adb.exec(cmd).also { response = it }
                }
                val client = AutoserviceClientImpl(recording)
                // Use the production parser. Float decoding is reversible for finite values;
                // retain its exact bits so integer error sentinels cannot become tiny floats.
                val bits = when (entry.transact) {
                    5 -> client.getIntRaw(entry.device, entry.fid)
                    7 -> client.getFloat(entry.device, entry.fid)?.toRawBits()
                    else -> error("Only getters allowed")
                }
                val raw = response
                when {
                    raw == null -> N9BatteryReading(raw = "<no ADB reply>", error = "NO_ADB_REPLY")
                    bits == null -> N9BatteryReading(raw = raw,
                        error = "PARSE_OR_FLOAT_SENTINEL_ERROR (see raw Parcel)")
                    else -> decode(entry, bits, raw, status = 0)
                }
            }
        }

        private fun helperReader(helper: HelperClient): suspend (FidEntry) -> N9BatteryReading = { entry ->
            val reply = helper.readBatch(listOf(BatchReadItem(entry.transact, entry.device, entry.fid)))
            val item = reply?.singleOrNull()
            if (item == null) {
                N9BatteryReading(raw = "<no valid Helper reply>", error = "HELPER_TRANSPORT_OR_REPLY_ERROR")
            } else {
                val (status, bits) = item
                val raw = "status=$status bits=0x${String.format(Locale.ROOT, "%08x", bits)} signedInt=$bits"
                if (status != 0) N9BatteryReading(raw = raw, status = status, error = "AUTOSERVICE_STATUS_ERROR")
                else decode(entry, bits, raw, status)
            }
        }

        private fun decode(entry: FidEntry, bits: Int, raw: String, status: Int): N9BatteryReading {
            fun invalid(reason: String) = N9BatteryReading(raw = raw, status = status, error = reason)
            if (SentinelDecoder.decodeInt(bits) == null) return invalid("INTEGER_SENTINEL")
            val decoded = when (entry.transact) {
                5 -> bits.toDouble()
                7 -> SentinelDecoder.parseFloatFromShellInt(bits)?.toDouble()
                    ?: return invalid("FLOAT_SENTINEL_OR_NONFINITE")
                else -> return invalid("NOT_A_GETTER")
            }
            val scaled = when (entry.field) {
                "minCellVoltage", "maxCellVoltage" -> decoded * entry.scale
                else -> decoded
            }
            val inRange = when (entry.field) {
                "soh", "soc" -> scaled in 0.0..100.0
                "totalElecConsumption" -> scaled >= 0.0
                "minCellVoltage", "maxCellVoltage" -> scaled in 0.5..6.0
                else -> false
            }
            return if (scaled.isFinite() && inRange) N9BatteryReading(scaled, raw, status)
            else invalid("OUT_OF_RANGE (decoded=$scaled)")
        }
    }
}

private fun n9Unit(field: String): String = when (field) {
    "soh", "soc" -> "%"
    "totalElecConsumption" -> "kWh"
    else -> "V"
}

private fun n9Number(value: Double): String =
    String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
