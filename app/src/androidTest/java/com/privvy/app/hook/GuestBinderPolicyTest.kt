package com.privvy.app.hook

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestBinderPolicyTest {

    @Test
    fun deniesDestructiveProcessControl() {
        assertTrue(GuestBinderPolicy.isDeniedProcessControl("killBackgroundProcesses"))
        assertTrue(GuestBinderPolicy.isDeniedProcessControl("forceStopPackage"))
        assertFalse(GuestBinderPolicy.isDeniedProcessControl("getRunningAppProcesses"))
        assertFalse(GuestBinderPolicy.isDeniedProcessControl("getMemoryInfo"))
    }

    @Test
    fun filtersProcessVisibilityToCurrentSpace() {
        val current = "com.privvy.app:v1"
        val host = "com.privvy.app"
        assertTrue(GuestBinderPolicy.isVisibleProcess(current, current, 1, host))
        assertTrue(GuestBinderPolicy.isVisibleProcess("com.privvy.app:v1", current, 1, host))
        assertTrue(GuestBinderPolicy.isVisibleProcess(host, current, 1, host))
        assertFalse(GuestBinderPolicy.isVisibleProcess("com.privvy.app:v2", current, 1, host))
        assertFalse(GuestBinderPolicy.isVisibleProcess("com.android.systemui", current, 1, host))
    }

    @Test
    fun rewritesOnlyGuestPackageArguments() {
        val guests = setOf("com.privvy.guesttest")
        assertEquals(
            "com.privvy.app",
            GuestBinderPolicy.rewritePackage("com.privvy.guesttest", guests, "com.privvy.app"),
        )
        assertEquals(
            "com.privvy.app",
            GuestBinderPolicy.rewritePackage("com.privvy.app", guests, "com.privvy.app"),
        )
        assertNull(GuestBinderPolicy.rewritePackage(null, guests, "com.privvy.app"))
    }

    @Test
    fun suppressesNotificationsOnlyWhenLocked() {
        assertTrue(GuestBinderPolicy.suppressNotification(true))
        assertFalse(GuestBinderPolicy.suppressNotification(false))
    }

    @Test
    fun guestIdentityRestoresPreviousPackage() {
        GuestIdentity.setProcessPackage("com.privvy.first")
        val result = GuestIdentity.withPackage("com.privvy.second") {
            assertEquals("com.privvy.second", GuestIdentity.currentPackage())
            42
        }
        assertEquals(42, result)
        assertEquals("com.privvy.first", GuestIdentity.currentPackage())
        GuestIdentity.setProcessPackage(null)
    }
}