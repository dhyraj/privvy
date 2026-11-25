package com.privvy.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestReceiverFilterTest {

    @Test
    fun receiverFilterJsonRoundTrip() {
        val filter = GuestReceiverFilter(
            receiverName = "com.privvy.guesttest.TestReceiver",
            exported = true,
            actions = listOf("com.privvy.guesttest.STATIC_PING"),
            categories = listOf("android.intent.category.DEFAULT"),
            dataSchemes = listOf("https"),
            dataHosts = listOf("example.test"),
            dataPaths = listOf("/ping"),
            dataMimeTypes = listOf("text/plain"),
        )
        val restored = GuestReceiverFilter.fromJson(filter.toJson())
        assertEquals(filter, restored)
    }

    @Test
    fun installedAppPreservesReceiverFilters() {
        val app = InstalledApp(
            packageName = "com.privvy.guesttest",
            label = "Guest Test",
            versionName = "1.0",
            versionCode = 1L,
            launchActivity = "com.privvy.guesttest.MainActivity",
            launchTheme = 0,
            apkPaths = listOf("/virtual/apks/com.privvy.guesttest/base.apk"),
            nativeLibDir = null,
            iconPath = null,
            installedAt = 1L,
            receiverFilters = listOf(
                GuestReceiverFilter(
                    receiverName = "com.privvy.guesttest.TestReceiver",
                    exported = true,
                    actions = listOf("com.privvy.guesttest.STATIC_PING"),
                ),
            ),
        )
        val restored = InstalledApp.fromJson(app.toJson())
        assertEquals(app, restored)
        assertTrue(restored!!.receiverFilters.single().exported)
    }
}