package com.privvy.app.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Binder-accessible counterpart to [VirtualMediaStore]. Collection URIs are
 * content://com.privvy.app.virtualmedia/<spaceId>; item URIs append <mediaId>.
 * Keep exported=false in the manifest: guest processes share Privvy's UID.
 */
class VirtualMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        val context = requireContext()
        return VirtualMediaStore.queryInternal(
            context = context,
            spaceId = target.spaceId,
            mediaId = target.mediaId,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            mediaType = null,
            limit = null,
            offset = 0,
        )
    }

    override fun getType(uri: Uri): String? {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        return if (target.mediaId == null) {
            VirtualMediaStore.COLLECTION_MIME_TYPE
        } else {
            VirtualMediaStore.mimeType(requireContext(), target.spaceId, target.mediaId)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        require(target.mediaId == null) { "Insert requires a collection URI: $uri" }
        requireNotNull(values) { "Media insert values cannot be null" }
        val context = requireContext()
        val result = VirtualMediaStore.insertInternal(
            context = context,
            spaceId = target.spaceId,
            values = values,
            ownerPackageName = callingPackage ?: context.packageName,
            mediaType = null,
        )
        notifyChanged(result)
        return result
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        val count = VirtualMediaStore.deleteInternal(
            context = requireContext(),
            spaceId = target.spaceId,
            mediaId = target.mediaId,
            selection = selection,
            selectionArgs = selectionArgs,
            mediaType = null,
        )
        if (count > 0) notifyChanged(uri)
        return count
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        requireNotNull(values) { "Media update values cannot be null" }
        val count = VirtualMediaStore.updateInternal(
            context = requireContext(),
            spaceId = target.spaceId,
            mediaId = target.mediaId,
            values = values,
            selection = selection,
            selectionArgs = selectionArgs,
            mediaType = null,
        )
        if (count > 0) notifyChanged(uri)
        return count
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = VirtualMediaStore.parseVirtualUri(uri)
        val mediaId = requireNotNull(target.mediaId) { "openFile requires an item URI: $uri" }
        return VirtualMediaStore.openFile(requireContext(), target.spaceId, mediaId, mode)
    }

    private fun notifyChanged(uri: Uri) {
        val resolver = requireContext().contentResolver
        val target = VirtualMediaStore.parseVirtualUri(uri)
        resolver.notifyChange(uri, null)
        resolver.notifyChange(VirtualMediaStore.collectionUri(target.spaceId), null)
    }
}
