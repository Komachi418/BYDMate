package com.bydmate.app.n9

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class N9MonitorSettingsTest {
    private lateinit var app: Application

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("n9_battery_monitor", 0).edit().clear().commit()
        N9Monitor.update(N9MonitorState())
        N9Monitor.serviceActive = false
        while (shadowOf(app).nextStartedService != null) { /* drain earlier test intents */ }
    }

    @Test fun `fresh install has no assumed capacity tariff or enabled recording`() {
        assertEquals(N9MonitorSettings(false, null, null), N9Monitor.settings(app))
        assertNull(shadowOf(app).nextStartedService)
        N9MonitorReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `boot resumes only an explicitly enabled monitor and stop persists disabled`() {
        N9Monitor.enable(app)
        assertTrue(N9Monitor.settings(app).enabled)
        assertEquals(N9MonitorService.ACTION_ENABLE, shadowOf(app).nextStartedService.action)
        N9MonitorReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(N9MonitorService.ACTION_ENABLE, shadowOf(app).nextStartedService.action)
        N9Monitor.update(N9MonitorState(running = true))
        N9MonitorReceiver().onReceive(app, Intent(N9MonitorService.ACTION_STOP))
        assertFalse(N9Monitor.settings(app).enabled)
        assertEquals(N9MonitorService.ACTION_STOP, shadowOf(app).nextStartedService.action)
        N9MonitorReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `one-shot refresh never silently enables background recording`() {
        N9Monitor.refresh(app)
        assertFalse(N9Monitor.settings(app).enabled)
        assertEquals(N9MonitorService.ACTION_REFRESH, shadowOf(app).nextStartedService.action)
    }

    @Test fun `settings retain zero tariff allow unknown capacity and reject invalid input`() {
        N9Monitor.saveSettings(app, 0.0, null)
        assertEquals(N9MonitorSettings(false, 0.0, null), N9Monitor.settings(app))
        N9Monitor.saveSettings(app, 0.68, 46.9)
        assertEquals(N9MonitorSettings(false, 0.68, 46.9), N9Monitor.settings(app))
        for ((tariff, capacity) in listOf(-1.0 to 46.9, Double.NaN to 46.9,
            0.68 to 0.0, 0.68 to Double.POSITIVE_INFINITY, 0.68 to 501.0)) {
            try {
                N9Monitor.saveSettings(app, tariff, capacity)
                fail("Invalid input must not be saved")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(N9MonitorSettings(false, 0.68, 46.9), N9Monitor.settings(app))
        N9Monitor.saveSettings(app, null, null)
        assertEquals(N9MonitorSettings(false, null, null), N9Monitor.settings(app))
    }

    @Test fun `unrelated broadcasts cannot enable recording`() {
        N9MonitorReceiver().onReceive(app, Intent("untrusted.action.ENABLE"))
        assertFalse(N9Monitor.settings(app).enabled)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `foreground launch failure publishes a new empty observation instead of stale readings`() {
        N9Monitor.update(N9MonitorState(latest = N9Sample(1, 1, mapOf("soc" to 90.0), "old")))
        val denied = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("background launch denied")
            }
        }
        N9Monitor.refresh(denied)
        assertFalse(N9Monitor.state.value.running)
        assertFalse(N9Monitor.state.value.sampling)
        assertTrue(N9Monitor.state.value.latest!!.values.isEmpty())
        assertNotNull(N9Monitor.state.value.latest!!.connectionError)
        assertTrue(N9Monitor.state.value.status.contains("未能启动"))
        assertFalse(N9Monitor.settings(app).enabled)
    }
}
