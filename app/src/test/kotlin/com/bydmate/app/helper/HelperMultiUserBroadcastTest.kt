package com.bydmate.app.helper

import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Looper
import android.os.UserHandle
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the actual publication path, including its API choice and repeat timer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class HelperMultiUserBroadcastTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val sends = mutableListOf<Pair<Intent, UserHandle>>()

        override fun sendBroadcast(intent: Intent) = error("Untargeted broadcast would use shell user 0")

        override fun sendBroadcastAsUser(intent: Intent, user: UserHandle) {
            sends += intent to user
        }
    }

    @Test fun `user 10 delivery keeps explicit receiver token and binder`() {
        val ctx = RecordingContext()
        val binder = Binder()
        publishBinderByBroadcast(ctx, binder, TOKEN, USER_10_UID)

        val (intent, user) = ctx.sends.single()
        assertEquals(UserHandle.getUserHandleForUid(USER_10_UID), user)
        assertFalse(UserHandle.getUserHandleForUid(10_123) == user)
        assertEquals(HelperBinderProtocol.APP_PACKAGE, intent.`package`)
        assertEquals(HelperBinderProtocol.APP_PACKAGE, intent.component?.packageName)
        assertEquals(HelperBinderProtocol.RECEIVER_CLASS, intent.component?.className)
        assertEquals(HelperBinderProtocol.ACTION_BINDER, intent.action)
        val payload = intent.getBundleExtra(HelperBinderProtocol.EXTRA_BUNDLE)!!
        assertEquals(TOKEN, payload.getString(HelperBinderProtocol.KEY_TOKEN))
        assertSame(binder, payload.getBinder(HelperBinderProtocol.KEY_BINDER))
        assertTrue(intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
    }

    @Test fun `owner user still receives its own binder`() {
        val ctx = RecordingContext()
        publishBinderByBroadcast(ctx, Binder(), TOKEN, 10_123)
        assertEquals(UserHandle.getUserHandleForUid(10_123), ctx.sends.single().second)
    }

    @Test fun `reannounce retains user 10 and respects force stop`() {
        val ctx = RecordingContext()
        scheduleReannounce(ctx, Binder(), TOKEN, USER_10_UID)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)

        val (intent, user) = ctx.sends.single()
        assertEquals(UserHandle.getUserHandleForUid(USER_10_UID), user)
        assertEquals(0, intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid uid fails before sending`() {
        publishBinderByBroadcast(RecordingContext(), Binder(), TOKEN, -1)
    }

    private companion object {
        const val USER_10_UID = 1_010_123
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
