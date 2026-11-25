package com.privvy.app.core.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.privvy.app.core.SpaceAppRegistry
import com.privvy.app.engine.GuestRuntimeFactory
import com.privvy.app.security.SessionLockManager

open class StubContentProviderBase : ContentProvider() {

    private val guestProviders = HashMap<Pair<String, String>, ContentProvider>()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        provider(uri)?.query(originalUri(uri), projection, selection, selectionArgs, sortOrder)

    override fun getType(uri: Uri): String? = provider(uri)?.getType(originalUri(uri))

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        provider(uri)?.insert(originalUri(uri), values)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        provider(uri)?.delete(originalUri(uri), selection, selectionArgs) ?: 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        provider(uri)?.update(originalUri(uri), values, selection, selectionArgs) ?: 0

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? =
        provider(uri)?.openFile(originalUri(uri), mode)

    override fun call(algorithm: String, method: String?, arg: Bundle?): Bundle? {
        val extras = arg ?: return null
        return callProvider(extras)?.call(originalAuthority(extras), method, cleanExtras(extras))
    }

    override fun call(algorithm: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        val callExtras = extras ?: return null
        return callProvider(callExtras)?.call(originalAuthority(callExtras), method, arg, cleanExtras(callExtras))
    }

    private fun callProvider(extras: Bundle): ContentProvider? {
        val packageName = extras.getString(EXTRA_PACKAGE) ?: return null
        val authority = extras.getString(EXTRA_AUTHORITY) ?: return null
        return provider(packageName, authority)
    }

    private fun originalAuthority(extras: Bundle): String =
        extras.getString(EXTRA_AUTHORITY).orEmpty()

    private fun cleanExtras(extras: Bundle): Bundle = Bundle(extras).apply {
        remove(EXTRA_PACKAGE)
        remove(EXTRA_AUTHORITY)
    }

    private fun provider(uri: Uri): ContentProvider? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        return provider(decode(segments[0]), decode(segments[1]))
    }

    private fun provider(packageName: String, authority: String): ContentProvider? {
        val key = packageName to authority
        guestProviders[key]?.let { return it }
        val context = context ?: return null
        if (!SessionLockManager.ensureGuestAllowed(context)) throw SecurityException("Privvy session is locked")
        val spaceId = com.privvy.app.core.EngineState.spaceId()
        val app = SpaceAppRegistry.list(context, spaceId).firstOrNull { it.packageName == packageName } ?: return null
        val info = context.packageManager.getPackageArchiveInfo(app.apkPaths.first(), PackageManager.GET_PROVIDERS)
            ?.providers?.firstOrNull { provider ->
                provider.authority?.split(';')?.contains(authority) == true
            } ?: return null
        val runtime = GuestRuntimeFactory.get(context, app)
        val provider = runtime.loader.loadClass(info.name).getDeclaredConstructor().newInstance() as ContentProvider
        provider.attachInfo(runtime.guestContext, info)
        guestProviders[key] = provider
        return provider
    }

    private fun originalUri(uri: Uri): Uri {
        val segments = uri.pathSegments
        if (segments.size < 2) return uri
        val authority = decode(segments[1])
        val builder = Uri.Builder().scheme("content").authority(authority)
        segments.drop(2).forEach(builder::appendPath)
        return builder.query(uri.encodedQuery).fragment(uri.encodedFragment).build()
    }

    private fun decode(value: String): String = String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))

    private companion object {
        const val EXTRA_PACKAGE = "privvy.guest.package"
        const val EXTRA_AUTHORITY = "privvy.guest.authority"
    }
}

class StubContentProvider_V0 : StubContentProviderBase()
class StubContentProvider_V1 : StubContentProviderBase()
class StubContentProvider_V2 : StubContentProviderBase()
class StubContentProvider_V3 : StubContentProviderBase()
