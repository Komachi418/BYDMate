package com.bydmate.app.n9

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.AdbKeyStore
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AdbOnDeviceClientImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class N9MonitorState(
    val running: Boolean = false,
    val sampling: Boolean = false,
    val latest: N9Sample? = null,
    val status: String = "尚未启用自动记录",
)

data class N9MonitorSettings(val enabled: Boolean, val tariff: Double?, val nominalKwh: Double?)

/** The only entry points for battery monitoring. No production app graph is loaded. */
object N9Monitor {
    private const val PREFS = "n9_battery_monitor"
    internal const val STOPPED_STATUS = "自动记录已关闭；已有记录保留"
    private val mutable = MutableStateFlow(N9MonitorState())
    val state: StateFlow<N9MonitorState> = mutable

    internal var adbFactory: (Context) -> AdbOnDeviceClient = {
        AdbOnDeviceClientImpl(it.applicationContext, AdbKeyStore(it.applicationContext))
    }
    internal var storeFactory: (Context) -> N9RecordingStore = { N9RecordingStore(it.applicationContext) }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var serviceActive = false

    internal fun update(value: N9MonitorState) { mutable.value = value }

    internal fun failedSample(detail: String) = N9Sample(System.currentTimeMillis(),
        SystemClock.elapsedRealtime(), emptyMap(), detail, detail)

    fun settings(context: Context): N9MonitorSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun number(key: String) = prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return N9MonitorSettings(prefs.getBoolean("enabled", false),
            number("tariff")?.takeIf { it in 0.0..100.0 },
            number("nominalKwh")?.takeIf { it > 0.0 && it <= 500.0 })
    }

    fun saveSettings(context: Context, tariff: Double?, nominalKwh: Double?) {
        require(tariff == null || tariff.isFinite() && tariff in 0.0..100.0)
        require(nominalKwh == null || nominalKwh.isFinite() && nominalKwh > 0.0 && nominalKwh <= 500.0)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tariff", tariff?.toString()).putString("nominalKwh", nominalKwh?.toString()).commit()) {
            "无法保存设置"
        }
    }

    internal fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).commit()) {
            "无法保存记录开关"
        }
    }

    fun enable(context: Context) {
        setEnabled(context, true)
        start(context, N9MonitorService.ACTION_ENABLE)
    }

    fun refresh(context: Context) = start(context, N9MonitorService.ACTION_REFRESH)

    private fun start(context: Context, action: String) {
        try {
            context.startForegroundService(Intent(context, N9MonitorService::class.java).setAction(action))
        } catch (failure: Exception) {
            val detail = "监测未能启动：${failure.javaClass.simpleName}，请打开应用重试"
            update(N9MonitorState(latest = failedSample(detail), status = detail))
        }
    }

    fun disable(context: Context) {
        setEnabled(context, false)
        // Persist first, including when STOP comes from a newly recreated receiver.
        if (serviceActive || mutable.value.running || mutable.value.sampling) {
            try {
                context.startService(Intent(context, N9MonitorService::class.java).setAction(N9MonitorService.ACTION_STOP))
            } catch (_: Exception) {
                context.stopService(Intent(context, N9MonitorService::class.java))
                update(N9MonitorState(status = "$STOPPED_STATUS；连续记录将在下次启动时标记中断"))
            }
        } else {
            context.stopService(Intent(context, N9MonitorService::class.java))
            update(mutable.value.copy(running = false, sampling = false, status = STOPPED_STATUS))
        }
    }
}

/** Visible, user-enabled read-only monitor. It never changes car/ADB settings,
 * acquires a wake lock, or attempts to keep the head unit powered on. */
class N9MonitorService : Service() {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var actor: Job? = null
    private var inFlight: Deferred<N9Sample>? = null
    private var adb: AdbOnDeviceClient? = null
    private var store: N9RecordingStore? = null
    private var initialized = false
    private var continuous = false
    private var generation = 0L
    private var latestStartId = 0
    private var destroyed = false
    private var terminal = false
    private var foregroundReady = false
    private val io get() = N9Monitor.ioDispatcher

    private data class Command(val action: String?, val startId: Int, val generation: Long)

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.N9_READ_ONLY)
        N9Monitor.serviceActive = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "电池自动记录", NotificationManager.IMPORTANCE_LOW))
            // Meet the foreground deadline before constructing keys, sockets or the database.
            startForeground(NOTIFICATION_ID, notification("准备读取电池；所有操作均为只读"))
            foregroundReady = true
            actor = scope.launch { runCommands() }
        } catch (failure: Exception) {
            terminal = true
            val detail = "监测未能启动：${failure.javaClass.simpleName}，请打开应用重试"
            N9Monitor.update(N9MonitorState(latest = N9Monitor.failedSample(detail), status = detail))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || destroyed || terminal) return START_NOT_STICKY
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            // Invalidate every older queued refresh, and interrupt the current read.
            // The command owner joins that read before touching the socket again.
            generation++
            inFlight?.cancel()
        }
        commands.trySend(Command(intent?.action, startId, generation))
        return if (N9Monitor.settings(this).enabled) START_STICKY else START_NOT_STICKY
    }

    private suspend fun runCommands() {
        try {
            while (coroutineContext.isActive) {
                val command = if (continuous) {
                    withTimeoutOrNull(INTERVAL_MS) { commands.receive() }
                        ?: Command(ACTION_TICK, latestStartId, generation)
                } else commands.receive()
                if (command.generation != generation) continue
                // A user may enable again while an older STOP is awaiting IO.
                // Apply the newer command after that STOP, in the same order.
                if (command.action == ACTION_ENABLE) withContext(io) {
                    N9Monitor.setEnabled(this@N9MonitorService, true)
                }
                val enabled = N9Monitor.settings(this).enabled
                if (command.action == ACTION_STOP ||
                    (!enabled && (command.action == null || command.action == ACTION_TICK))) {
                    stopRecording(command.startId)
                    continue
                }
                if (command.action !in listOf(ACTION_ENABLE, ACTION_REFRESH, ACTION_TICK, null)) continue
                if (!enabled && command.action != ACTION_REFRESH) {
                    stopRecording(command.startId)
                    continue
                }
                prepare()
                if (command.generation != generation) continue
                continuous = enabled
                try {
                    sampleOnce(command)
                } catch (cancelled: CancellationException) {
                    coroutineContext.ensureActive()
                    if (command.generation == generation) throw cancelled
                }
                if (command.generation != generation) continue
                if (!continuous) {
                    withContext(io) {
                        try { store!!.finishOpen("单次刷新结束，未开启连续记录") }
                        finally { closeAdb() }
                    }
                    finishService(command.startId)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!destroyed && coroutineContext.isActive) failAndStop(cancelled)
            else throw cancelled
        } catch (failure: Exception) {
            if (!destroyed) failAndStop(failure)
        } finally {
            withContext(NonCancellable + io) { closeAdb() }
        }
    }

    private suspend fun prepare() = withContext(io) {
        if (store == null) store = N9Monitor.storeFactory(applicationContext)
        if (!initialized) {
            // This also prevents a failed prior save from bridging a later session.
            store!!.finishOpen("进程重启，连续记录中断")
            initialized = true
        }
        coroutineContext.ensureActive()
        if (adb == null) adb = N9Monitor.adbFactory(applicationContext)
    }

    private suspend fun sampleOnce(command: Command) {
        N9Monitor.update(N9MonitorState(continuous, true, null, "正在读取并保存电池数据"))
        val read = scope.async(io) {
            val sample = N9TelemetryReader(adb!!).read()
            coroutineContext.ensureActive()
            store!!.record(sample)
            coroutineContext.ensureActive()
            if (!sample.hasReadings) closeAdb()
            sample
        }
        inFlight = read
        try {
            val result = read.await()
            coroutineContext.ensureActive()
            if (destroyed || command.generation != generation) return
            val status = if (result.connectionError != null || !result.hasReadings) {
                if (continuous) "本次无有效读数，已保留中断；等待连接恢复"
                else "本次无有效读数，已保存失败记录"
            } else if (continuous) "自动记录中 · 充电与行程自动分段" else "本次刷新已保存"
            N9Monitor.update(N9MonitorState(continuous, false, result, status))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } finally {
            // Socket operations and SQLite calls may not be promptly cancellable.
            // Never allow STOP or destruction to race their eventual completion.
            withContext(NonCancellable) { read.cancelAndJoin() }
            inFlight = null
            if (!destroyed) N9Monitor.update(N9Monitor.state.value.copy(sampling = false))
        }
    }

    private suspend fun stopRecording(startId: Int) {
        continuous = false
        withContext(io) {
            try {
                if (N9Monitor.settings(this@N9MonitorService).enabled) N9Monitor.setEnabled(this@N9MonitorService, false)
                if (store == null) store = N9Monitor.storeFactory(applicationContext)
                store!!.finishOpen("用户停止自动记录")
            } finally { closeAdb() }
        }
        N9Monitor.update(N9Monitor.state.value.copy(running = false, sampling = false, status = N9Monitor.STOPPED_STATUS))
        finishService(startId)
    }

    private suspend fun failAndStop(failure: Exception) {
        continuous = false
        terminal = true
        val detail = "读取或保存失败：${failure.javaClass.simpleName}；监测已停止，本次数据不可用，请重试"
        N9Monitor.update(N9MonitorState(latest = N9Monitor.failedSample(
            "$detail\n${failure.message.orEmpty().take(500)}"), status = detail))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        withContext(NonCancellable + io) {
            // Failure to write this interruption cannot turn the unsaved sample into success.
            // prepare() repeats the interruption on the next service instance.
            runCatching { store?.finishOpen("读取或保存失败，连续记录中断") }
            closeAdb()
        }
    }

    private fun finishService(startId: Int) {
        // A newer explicit refresh may already be queued; keep its foreground service.
        if (startId != latestStartId) return
        // Android may know about a pending start not yet delivered to this instance.
        if (stopSelfResult(startId)) {
            terminal = true
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun closeAdb() {
        val connection = adb ?: return
        // Cancellation arriving during shutdown must not lose the last reference
        // to a still-open connection before the command owner can clean it up.
        try { withContext(NonCancellable) { runCatching { connection.shutdown() } } }
        finally { adb = null }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, N9BatteryActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, N9MonitorReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("BYDMate N9 电池").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止自动记录", stop).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        N9Monitor.serviceActive = false
        lifetime.cancel()
        commands.close()
        if (!terminal) {
            val detail = "监测已中断；本次读取不可用，重新开启后将单独记录"
            N9Monitor.update(N9MonitorState(latest = N9Monitor.failedSample(detail), status = detail))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Wait for the canceled reader/command owner. No parallel shutdown can
        // accidentally close a socket while a late read is still using it.
        CoroutineScope(io).launch {
            actor?.join()
            if (!terminal) runCatching { store?.finishOpen("服务退出，连续记录中断") }
            closeAdb()
            runCatching { store?.close() }
            store = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENABLE = "com.bydmate.app.n9.ENABLE"
        const val ACTION_REFRESH = "com.bydmate.app.n9.REFRESH"
        const val ACTION_STOP = "com.bydmate.app.n9.STOP"
        private const val ACTION_TICK = "com.bydmate.app.n9.TICK"
        private const val CHANNEL = "n9_battery_recording"
        private const val NOTIFICATION_ID = 506
        private const val INTERVAL_MS = 15_000L
    }
}

class N9MonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                N9MonitorService.ACTION_STOP -> N9Monitor.disable(context)
                Intent.ACTION_BOOT_COMPLETED -> if (N9Monitor.settings(context).enabled) N9Monitor.enable(context)
            }
        } catch (failure: Exception) {
            val detail = "无法更新记录开关：${failure.javaClass.simpleName}，请打开应用重试"
            N9Monitor.update(N9MonitorState(latest = N9Monitor.failedSample(detail), status = detail))
        }
    }
}
