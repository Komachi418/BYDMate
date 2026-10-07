package com.bydmate.app.n9

import android.os.SystemClock
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AutoserviceClientImpl
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.Decoder
import com.bydmate.app.data.nativestack.FidMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class N9Sample(
    val timestamp: Long,
    val monotonicMs: Long,
    val values: Map<String, Double>,
    val raw: String,
    val connectionError: String? = null,
) {
    val hasReadings: Boolean get() = values.isNotEmpty()
}

data class N9BatteryField(
    val key: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val verifiedOnN9: Boolean,
)

object N9Fields {
    /** Verified means the V1 N9 scalar read was observed, not a battery-health verdict. */
    val definitions: List<N9BatteryField> = listOf(
        N9BatteryField("soh", "SoH（车机上报）", "%", 0, true),
        N9BatteryField("soc", "SOC", "%", 1, true),
        N9BatteryField("totalElecConsumption", "累计电量（车机计数）", "kWh", 3, true),
        N9BatteryField("minCellVoltage", "最低电芯电压", "V", 3, true),
        N9BatteryField("maxCellVoltage", "最高电芯电压", "V", 3, true),
        N9BatteryField("batteryRemainKwh", "BMS 剩余电量", "kWh", 2, false),
        N9BatteryField("voltage12v", "低压电池电压", "V", 2, false),
        N9BatteryField("hvVoltage", "高压电池总压", "V", 0, false),
        N9BatteryField("hvCurrent", "电池电流（负入正出）", "A", 2, false),
        N9BatteryField("maxBatTemp", "最高电池温度", "℃", 0, false),
        N9BatteryField("minBatTemp", "最低电池温度", "℃", 0, false),
        N9BatteryField("insulationKohm", "高压绝缘电阻", "kΩ", 0, false),
        N9BatteryField("bmsMaxChargeKw", "BMS 允许充电功率", "kW", 1, false),
        N9BatteryField("bmsMaxDischargeAllowKw", "BMS 允许放电功率", "kW", 1, false),
        N9BatteryField("bmsMaxDischargeKw", "BMS 可用功率", "kW", 0, false),
        N9BatteryField("chargeGunState", "充电枪状态码", "", 0, false),
        N9BatteryField("bmsState", "BMS 充电状态码", "", 0, false),
        N9BatteryField("chargingType", "充电类型码", "", 0, false),
        N9BatteryField("chargingCapacity", "本次充电计数（可能重置）", "kWh", 3, false),
        N9BatteryField("chargerConnectState", "充电器连接码", "", 0, false),
        N9BatteryField("chargeConnectIndicator", "仪表充电连接码", "", 0, false),
        N9BatteryField("mileage", "BMS 总里程", "km", 1, false),
        N9BatteryField("speed", "车速", "km/h", 1, false),
        N9BatteryField("powerState", "车辆上电状态码", "", 0, false),
        N9BatteryField("motorCurrentFront", "前电机电流", "A", 2, false),
        N9BatteryField("motorCurrentRear", "后电机电流", "A", 2, false),
        N9BatteryField("compressorW", "压缩机功率", "W", 0, false),
        N9BatteryField("power", "驱动功率接口", "kW", 0, false),
        N9BatteryField("batteryType", "电池类型码", "", 0, false),
        N9BatteryField("keyBatteryStatus", "钥匙电池状态码", "", 0, false),
        N9BatteryField("batteryPowerKw", "电池功率估算（负入正出）", "kW", 3, false),
        N9BatteryField("frontPowerKw", "前电机电功率估算", "kW", 3, false),
        N9BatteryField("rearPowerKw", "后电机电功率估算", "kW", 3, false),
        N9BatteryField("compressorKw", "压缩机功率", "kW", 3, false),
        N9BatteryField("cellDeltaMv", "电芯压差", "mV", 1, true),
        N9BatteryField("tempDeltaC", "电池温差", "℃", 0, false),
    )
}

/** One sequential sample from a fixed getter list. No Helper bootstrap, grants, pairing,
 * recovery commands or catalog scans. The owning service controls polling and shutdown. */
class N9TelemetryReader(private val adb: AdbOnDeviceClient) {
    suspend fun read(): N9Sample {
        coroutineContext.ensureActive()
        val trace = Trace()
        trace.line("N9 telemetry: sequential read; extended fields/scales/polarity not verified on N9")
        trace.line("readStartedAt=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()}")
        var connectedNow = connected(trace)
        if (!connectedNow) {
            val result: Result<Unit> = try {
                adb.connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            coroutineContext.ensureActive()
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            connectedNow = connected(trace)
            trace.line("classic5555 handshake=${result.isSuccess} connected=$connectedNow")
            if (result.isFailure || !connectedNow) {
                val reason = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "classic5555 not connected after handshake"
                trace.line("classic5555 failure=${lastConnectFailure()}; $reason")
                trace.line("autoservice probe=NOT_RUN")
                return snapshot(emptyMap(), trace, reason.take(512))
            }
        } else {
            trace.line("classic5555 reused connected=true")
        }

        val recording = object : AdbOnDeviceClient by adb {
            override suspend fun exec(cmd: String): String? {
                coroutineContext.ensureActive()
                trace.line("\$ $cmd")
                return try {
                    adb.exec(cmd).also { response ->
                        coroutineContext.ensureActive()
                        trace.line(response ?: "<no ADB reply>")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    trace.line("<read error ${failure.javaClass.simpleName}: ${failure.message}>")
                    throw failure
                }
            }
        }
        val client = AutoserviceClientImpl(recording)
        val values = linkedMapOf<String, Double>()
        for (spec in SPECS) {
            coroutineContext.ensureActive()
            try {
                val entry = FidMap.byField.getValue(spec.key)
                check(entry.transact == spec.tx && (spec.tx == 5 || spec.tx == 7)) { "Unexpected getter type" }
                val value = if (spec.tx == 5) {
                    val bits = client.getIntRaw(entry.device, entry.fid)
                    if (bits == null) null
                    else if (SentinelDecoder.decodeInt(bits) == null) {
                        trace.line("${spec.key}: INTEGER_SENTINEL raw=$bits")
                        null
                    } else when (entry.decoder) {
                        Decoder.INT_SCALED -> bits * entry.scale
                        Decoder.INT_TEMP_C_OFS40 -> bits.toDouble() - 40.0
                        else -> bits.toDouble()
                    }
                } else {
                    val value = client.getFloat(entry.device, entry.fid)
                    if (value != null && SentinelDecoder.decodeInt(value.toRawBits()) == null) {
                        trace.line("${spec.key}: INTEGER_SENTINEL_IN_FLOAT")
                        null
                    } else value?.toDouble()
                }
                when {
                    value == null -> trace.line("${spec.key}=unavailable (no parsed value/sentinel; see raw)")
                    !value.isFinite() || value !in spec.range ->
                        trace.line("${spec.key}=unavailable (range ${spec.range}, decoded=$value)")
                    else -> {
                        values[spec.key] = value
                        trace.line("${spec.key}=$value")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                trace.line("${spec.key}=unavailable (${failure.javaClass.simpleName}: ${failure.message})")
            }
        }
        if (values["batteryRemainKwh"] == 0.0 && (values["soc"] ?: 0.0) > 0.0) {
            values.remove("batteryRemainKwh")
            trace.line("batteryRemainKwh=unavailable (zero energy with positive SOC; no SOC-derived replacement)")
        }
        derive(values, trace)
        val connectedAfter = connected(trace)
        trace.line("classic5555 connected after reads=$connectedAfter")
        trace.line("autoservice probe=${if (values.isEmpty()) "NO_VALID_READING" else "VALID_READING"}")
        return snapshot(values.toMap(), trace, if (connectedAfter) null else "classic5555 disconnected during sample")
    }

    private suspend fun connected(trace: Trace): Boolean = try {
        adb.isConnected().also { coroutineContext.ensureActive() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        trace.line("classic5555 connection check failed=${failure.javaClass.simpleName}: ${failure.message}")
        false
    }

    private fun lastConnectFailure(): String = try {
        adb.lastConnectFailure()?.toString() ?: "none"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.javaClass.simpleName
    }

    private fun snapshot(values: Map<String, Double>, trace: Trace, error: String?): N9Sample {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        trace.line("readFinishedAt=$wall elapsedMs=$elapsed")
        return N9Sample(wall, elapsed, values, trace.text(), error)
    }

    private fun derive(values: MutableMap<String, Double>, trace: Trace) {
        fun product(key: String, a: String, b: String, scale: Double) {
            val left = values[a] ?: return
            val right = values[b] ?: return
            val result = left * right * scale
            if (result.isFinite()) {
                values[key] = result
                trace.line("$key=$result (derived $a * $b * $scale; sequential observations)")
            }
        }
        product("batteryPowerKw", "hvVoltage", "hvCurrent", 0.001)
        product("frontPowerKw", "hvVoltage", "motorCurrentFront", 0.001)
        product("rearPowerKw", "hvVoltage", "motorCurrentRear", 0.001)
        values["compressorW"]?.let { values["compressorKw"] = it / 1000.0 }
        fun difference(key: String, min: String, max: String, scale: Double) {
            val low = values[min] ?: return
            val high = values[max] ?: return
            if (high >= low) values[key] = (high - low) * scale
            else trace.line("$key=unavailable (max below min)")
        }
        difference("cellDeltaMv", "minCellVoltage", "maxCellVoltage", 1000.0)
        difference("tempDeltaC", "minBatTemp", "maxBatTemp", 1.0)
    }

    private data class Spec(val key: String, val tx: Int, val range: ClosedFloatingPointRange<Double>)

    /** Bounds are plausibility filters, not measured N9 limits. Static FidMap scales only:
     * mileage remains 0.1 km per raw unit; no catalog substitution without its paired scale. */
    private companion object {
        val SPECS = listOf(
            Spec("soh", 5, 0.0..100.0),
            Spec("soc", 7, 0.0..100.0),
            Spec("totalElecConsumption", 7, 0.0..1_000_000_000.0),
            Spec("minCellVoltage", 5, 0.5..6.0),
            Spec("maxCellVoltage", 5, 0.5..6.0),
            Spec("batteryRemainKwh", 7, 0.0..1000.0),
            Spec("voltage12v", 7, 0.0..60.0),
            Spec("hvVoltage", 5, 0.0..1000.0),
            Spec("hvCurrent", 7, -2000.0..2000.0),
            Spec("maxBatTemp", 5, -50.0..80.0),
            Spec("minBatTemp", 5, -50.0..80.0),
            Spec("insulationKohm", 5, 0.0..65000.0),
            Spec("bmsMaxChargeKw", 5, 0.0..1000.0),
            Spec("bmsMaxDischargeAllowKw", 5, 0.0..1000.0),
            Spec("bmsMaxDischargeKw", 5, 0.0..1000.0),
            Spec("chargeGunState", 5, 0.0..5.0),
            Spec("bmsState", 5, 0.0..255.0),
            Spec("chargingType", 5, 0.0..5.0),
            Spec("chargingCapacity", 7, 0.0..1000.0),
            Spec("chargerConnectState", 5, 0.0..255.0),
            Spec("chargeConnectIndicator", 5, 0.0..255.0),
            Spec("mileage", 5, 0.0..10_000_000.0),
            Spec("speed", 7, 0.0..400.0),
            Spec("powerState", 5, 0.0..2.0),
            Spec("motorCurrentFront", 7, -2000.0..2000.0),
            Spec("motorCurrentRear", 7, -2000.0..2000.0),
            Spec("compressorW", 5, 0.0..20000.0),
            Spec("power", 5, -1000.0..1000.0),
            Spec("batteryType", 5, 0.0..255.0),
            Spec("keyBatteryStatus", 5, 0.0..255.0),
        )
    }

    private class Trace {
        private val content = StringBuilder()
        private var truncated = false
        fun line(text: String) {
            if (truncated) return
            val marker = "\n<raw diagnostic truncated at 16384 characters>"
            val remaining = 16_384 - content.length
            if (text.length + 1 <= remaining - marker.length) content.appendLine(text)
            else {
                content.append(text.take((remaining - marker.length).coerceAtLeast(0)))
                content.append(marker)
                truncated = true
            }
        }
        fun text(): String = content.toString()
    }
}
