package com.bydmate.app.n9

import android.os.Binder
import android.os.Parcel
import com.bydmate.app.BuildConfig
import com.bydmate.app.helper.autoserviceTransact
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class N9HelperScalarReplyTest {
    private fun read(replyWords: List<Int>, accepted: Boolean = true): Pair<Int, Int> {
        assertTrue(BuildConfig.N9_READ_ONLY)
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(7, code)
                data.enforceInterface("n9.test.autoservice")
                assertEquals(1014, data.readInt())
                assertEquals(1246777400, data.readInt())
                assertEquals(0, data.dataAvail())
                replyWords.forEach { reply!!.writeInt(it) }
                return accepted
            }
        }
        return autoserviceTransact(service, "n9.test.autoservice", 7, 1014, 1246777400, 0, false)
    }

    @Test fun `complete zero scalar remains a valid zero`() {
        assertEquals(0 to 0, read(listOf(0, 0)))
    }

    @Test fun `missing scalar never becomes valid zero`() {
        assertEquals(-999 to 0, read(emptyList()))
        assertEquals(-999 to 0, read(listOf(0)))
    }

    @Test fun `rejected transact cannot supply a valid reading`() {
        assertEquals(-999 to 0, read(listOf(0, 0x42c40000), accepted = false))
    }

    @Test fun `complete scalar preserves raw bits and error status`() {
        assertEquals(0 to 0x42c40000, read(listOf(0, 0x42c40000)))
        assertEquals(-1 to 0, read(listOf(-1, 0)))
    }
}
