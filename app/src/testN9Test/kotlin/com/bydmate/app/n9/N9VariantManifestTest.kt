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
    @Test fun `merged test manifest exposes only diagnostic entry points`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS)
        assertEquals("com.bydmate.app.n9test2", BuildConfig.APPLICATION_ID)
        assertEquals(505, BuildConfig.VERSION_CODE)
        assertEquals(505L, info.longVersionCode)
        assertEquals("3.19.9-n9-battery1", BuildConfig.VERSION_NAME)
        assertEquals("BYDMate N9 电池", app.applicationInfo.loadLabel(app.packageManager))
        assertTrue(BuildConfig.N9_READ_ONLY)
        assertEquals("android.app.Application", info.applicationInfo!!.className)
        assertEquals(listOf("com.bydmate.app.n9.N9DiagnosticsActivity"), info.activities.orEmpty().map { it.name })
        assertEquals(listOf("com.bydmate.app.helper.HelperBinderReceiver"), info.receivers.orEmpty().map { it.name })
        assertTrue(info.services.isNullOrEmpty())
        assertTrue(info.providers.isNullOrEmpty())
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.WRITE_SECURE_SETTINGS" in permissions)
        assertFalse("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in permissions)
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
    }
}
