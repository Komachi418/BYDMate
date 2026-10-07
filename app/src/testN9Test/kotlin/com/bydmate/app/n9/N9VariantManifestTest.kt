package com.bydmate.app.n9

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class N9VariantManifestTest {
    @Test fun `merged battery manifest exposes only isolated read-only components`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS)
        assertEquals("com.bydmate.app.n9test2", BuildConfig.APPLICATION_ID)
        assertEquals(506, BuildConfig.VERSION_CODE)
        assertEquals(506L, info.longVersionCode)
        assertEquals("3.19.9-n9-battery2", BuildConfig.VERSION_NAME)
        assertEquals("BYDMate N9 电池", app.applicationInfo.loadLabel(app.packageManager))
        assertTrue(BuildConfig.N9_READ_ONLY)
        assertEquals("android.app.Application", info.applicationInfo!!.className)
        assertEquals(setOf("com.bydmate.app.n9.N9BatteryActivity", "com.bydmate.app.n9.N9DiagnosticsActivity"), info.activities.orEmpty().map { it.name }.toSet())
        assertEquals(setOf("com.bydmate.app.helper.HelperBinderReceiver", "com.bydmate.app.n9.N9MonitorReceiver"), info.receivers.orEmpty().map { it.name }.toSet())
        assertEquals(listOf("com.bydmate.app.n9.N9MonitorService"), info.services.orEmpty().map { it.name })
        assertFalse(info.services!!.single().exported)
        assertFalse(info.receivers!!.single { it.name.endsWith("N9MonitorReceiver") }.exported)
        assertFalse(info.activities!!.single { it.name.endsWith("N9DiagnosticsActivity") }.exported)
        assertTrue(info.providers.isNullOrEmpty())
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.WRITE_SECURE_SETTINGS" in permissions)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertFalse("android.permission.WAKE_LOCK" in permissions)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in permissions)
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
    }
}
