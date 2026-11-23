package com.privvy.app.core.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Base stub for guest ContentProviders (spec §5.1).
 *
 * Beyond serving guest providers, this is the anchor point for the
 * Scoped-Storage MediaStore proxy (FR-06, Milestone 2): guest insertions of
 * media must be answered with a ParcelFileDescriptor pointing inside
 * virtual/user_<id>/sdcard/.
 */
open class StubContentProviderBase : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    @Throws(java.io.FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? = null
}

class StubContentProvider_V0 : StubContentProviderBase()
class StubContentProvider_V1 : StubContentProviderBase()
class StubContentProvider_V2 : StubContentProviderBase()
class StubContentProvider_V3 : StubContentProviderBase()
