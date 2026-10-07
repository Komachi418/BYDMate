package com.bydmate.app.data.autoservice

import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.BuildConfig
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run under testN9TestUnitTest: real public methods, with a socket-free protocol recorder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class N9AdbReadOnlyTest {
    private class RecordingProtocol : AdbProtocol {
        val commands = mutableListOf<String>()
        override val lastConnectFailure: AdbConnectFailure? = null
        override fun connect() = true
        override fun isConnected() = true
        override fun disconnect() = Unit
        override fun exec(cmd: String): String {
            commands += cmd
            return ""
        }
    }

    @Test fun `N9 permission grants never reach the connected shell`() = runTest {
        assumeTrue(BuildConfig.N9_READ_ONLY)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fake = RecordingProtocol()
        val client = AdbOnDeviceClientImpl(ctx, AdbKeyStore(ctx)).apply { protocolFactory = { fake } }
        assertTrue(client.connect().isSuccess)

        for (pkg in listOf(BuildConfig.APPLICATION_ID, "com.bydmate.app")) {
            assertFalse(client.grantUsageStatsAppop(pkg))
            assertFalse(client.grantWriteSecureSettings(pkg))
        }
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun `N9 spawn kill and log commands only name the independent daemon`() = runTest {
        assumeTrue(BuildConfig.N9_READ_ONLY)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fake = RecordingProtocol()
        val client = AdbOnDeviceClientImpl(ctx, AdbKeyStore(ctx)).apply { protocolFactory = { fake } }
        client.connect()
        assertTrue(client.spawnHelper("0123456789abcdef0123456789abcdef"))
        assertTrue(client.killHelper())
        client.readHelperLog()

        assertTrue(fake.commands.all { HelperBinderProtocol.PROCESS_NAME in it })
        assertTrue(fake.commands.none { "bydmate_helper" in it })
        assertTrue(fake.commands.first().contains("--nice-name=${HelperBinderProtocol.PROCESS_NAME} "))
        assertTrue(fake.commands.first().contains(ctx.packageCodePath))
    }
}
