package com.privvy.app.engine

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestPermissionStoreTest {
    private lateinit var root: File
    private lateinit var context: Context
    private var hostPermissions = mutableSetOf<String>()

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "guest-permissions-test-${System.nanoTime()}")
        check(root.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getApplicationInfo(): ApplicationInfo =
                ApplicationInfo(target.applicationInfo).apply { dataDir = root.absolutePath }

            override fun checkSelfPermission(permission: String): Int =
                if (permission in hostPermissions) {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun usesFrameworkPermissionRequestExtra() {
        val permissions = arrayOf(Manifest.permission.CAMERA)
        val intent = Intent("android.content.pm.action.REQUEST_PERMISSIONS")
            .putExtra(PrivvyInstrumentation.REQUEST_PERMISSIONS_NAMES, permissions)

        assertEquals(
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES",
            PrivvyInstrumentation.REQUEST_PERMISSIONS_NAMES,
        )
        assertEquals(
            permissions.toList(),
            intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")?.toList(),
        )
    }

    @Test
    fun defaultsRuntimePermissionsToDeniedAndNormalPermissionsToGranted() {
        assertFalse(GuestPermissionStore.isGranted(context, 0, GUEST, Manifest.permission.CAMERA))
        assertTrue(GuestPermissionStore.isGranted(context, 0, GUEST, Manifest.permission.INTERNET))
    }

    @Test
    fun persistsAllowAndDenyPerSpaceAndPackage() {
        GuestPermissionStore.setGranted(context, 0, GUEST, Manifest.permission.CAMERA, true)
        GuestPermissionStore.setGranted(context, 0, OTHER_GUEST, Manifest.permission.CAMERA, true)
        GuestPermissionStore.setGranted(context, 0, OTHER_GUEST, Manifest.permission.RECORD_AUDIO, true)
        GuestPermissionStore.setGranted(context, 1, GUEST, Manifest.permission.CAMERA, true)
        GuestPermissionStore.setGranted(context, 1, OTHER_GUEST, Manifest.permission.RECORD_AUDIO, false)

        assertTrue(GuestPermissionStore.isGranted(context, 0, GUEST, Manifest.permission.CAMERA))
        assertTrue(GuestPermissionStore.isGranted(context, 1, GUEST, Manifest.permission.CAMERA))
        assertFalse(GuestPermissionStore.isGranted(context, 2, GUEST, Manifest.permission.CAMERA))
        assertFalse(GuestPermissionStore.isGranted(context, 0, "com.privvy.unlisted", Manifest.permission.CAMERA))
        assertTrue(GuestPermissionStore.isGranted(context, 0, OTHER_GUEST, Manifest.permission.RECORD_AUDIO))
        assertFalse(GuestPermissionStore.isGranted(context, 1, OTHER_GUEST, Manifest.permission.RECORD_AUDIO))

        GuestPermissionStore.setGranted(context, 0, GUEST, Manifest.permission.CAMERA, false)
        assertFalse(GuestPermissionStore.isGranted(context, 0, GUEST, Manifest.permission.CAMERA))
        assertTrue(GuestPermissionStore.isGranted(context, 1, GUEST, Manifest.permission.CAMERA))
    }

    @Test
    fun requiresBothHostAndGuestGrants() {
        GuestPermissionStore.setGranted(context, 0, GUEST, Manifest.permission.CAMERA, true)

        assertEquals(
            PackageManager.PERMISSION_DENIED,
            GuestPermissionStore.checkSelfPermission(context, 0, GUEST, Manifest.permission.CAMERA),
        )
        hostPermissions.add(Manifest.permission.CAMERA)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            GuestPermissionStore.checkSelfPermission(context, 0, GUEST, Manifest.permission.CAMERA),
        )
        GuestPermissionStore.setGranted(context, 0, GUEST, Manifest.permission.CAMERA, false)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            GuestPermissionStore.checkSelfPermission(context, 0, GUEST, Manifest.permission.CAMERA),
        )
    }

    private companion object {
        const val GUEST = "com.privvy.guesttest"
        const val OTHER_GUEST = "com.privvy.otherguest"
    }
}
