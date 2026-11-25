package com.privvy.app.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privvy.app.core.VirtualStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class VirtualContentResolverTest {
    @Test
    fun outputStreamWritesWholeFileAndPublishesMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spaceId = VirtualStorage.SPACE_SLOTS - 1
        VirtualStorage.ensureSpace(context, spaceId)
        val resolver = VirtualContentResolver(context, spaceId, "com.privvy.test.guest")
        val bitmap = Bitmap.createBitmap(17, 11, Bitmap.Config.ARGB_8888)
        val expected = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val name = "resolver-${System.nanoTime()}.jpg"
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PrivvyTests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        try {
            val output = checkNotNull(resolver.openOutputStream(uri, "wt"))
            output.use { it.write(expected) }
            assertEquals(
                1,
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null),
            )
            val actual = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(expected, actual)
            checkNotNull(
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                    null,
                    null,
                    null,
                ),
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(expected.size.toLong(), cursor.getLong(0))
                assertEquals(17, cursor.getInt(1))
                assertEquals(11, cursor.getInt(2))
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
