package com.privvy.app.media

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileNotFoundException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Per-guest resolver. External MediaStore calls are served from this resolver's
 * space; every non-media call is forwarded to the application's real resolver.
 *
 * ContentResolver's CRUD/query/open methods are final on Android. Its existing
 * hidden ContentInterface wrapper is therefore installed reflectively rather
 * than pretending those final methods can be overridden. Construction fails
 * closed if the wrapper cannot be installed, so an accidental fallback cannot
 * send a guest media write to the host MediaProvider.
 */
class VirtualContentResolver(
    context: Context,
    private val spaceId: Int,
    private val guestPackageName: String,
    private val guestProviderAuthorities: Set<String> = emptySet(),
) : ContentResolver(context) {
    private val appContext = context.applicationContext ?: context
    private val realResolver = appContext.contentResolver
    private val delegateWrapper: Any by lazy {
        ContentResolver::class.java.getDeclaredField("mWrapped")
            .apply { isAccessible = true }
            .get(realResolver)
            .let(::checkNotNull)
    }

    init {
        VirtualMediaStore.requireValidSpaceId(spaceId)
        require(guestPackageName.isNotBlank()) { "Guest package name cannot be blank" }
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/content/ContentResolver;",
            "Landroid/content/ContentResolver\$ContentInterface;",
            "Landroid/content/ContentProviderOperation;",
        )
        installWrapper()
    }

    private fun installWrapper() {
        val field = ContentResolver::class.java.getDeclaredField("mWrapped")
        field.isAccessible = true
        val wrapperType = field.type
        val wrapper = Proxy.newProxyInstance(
            wrapperType.classLoader ?: javaClass.classLoader,
            arrayOf(wrapperType),
            WrapperHandler(),
        )
        field.set(this, wrapper)
    }

    private inner class WrapperHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "VirtualContentResolver($spaceId, $guestPackageName)"
                    else -> error("Unexpected Object method: ${method.name}")
                }
            }
            val a = args ?: emptyArray()
            guestProviderArguments(method.name, a)?.let { mapped ->
                return try {
                    method.invoke(delegateWrapper, *mapped)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
            return when (method.name) {
                "insert" -> insert(a)
                "bulkInsert" -> bulkInsert(a)
                "query" -> query(a)
                "delete" -> delete(a)
                "update" -> update(a)
                "openFile" -> openFile(a)
                "openAssetFile" -> openAssetFile(a)
                "openTypedAssetFile" -> openTypedAssetFile(a)
                "getType" -> getType(a[0] as Uri)
                "getStreamTypes" -> getStreamTypes(a[0] as Uri, a[1] as String)
                "canonicalize", "uncanonicalize" -> {
                    val uri = a[0] as Uri
                    if (targetFor(uri) != null || uri.authority in guestProviderAuthorities) {
                        uri
                    } else if (method.name == "canonicalize") {
                        realResolver.canonicalize(uri)
                    } else {
                        realResolver.uncanonicalize(uri)
                    }
                }
                "refresh" -> {
                    val uri = a[0] as Uri
                    val signal = a.getOrNull(2) as? CancellationSignal
                    signal?.throwIfCanceled()
                    if (targetFor(uri) != null) false
                    else realResolver.refresh(uri, a.getOrNull(1) as? Bundle, signal)
                }
                "checkUriPermission" -> {
                    val uri = a[0] as Uri
                    if (targetFor(uri) != null) PackageManager.PERMISSION_GRANTED
                    else appContext.checkUriPermission(uri, a[1] as Int, a[2] as Int, a[3] as Int)
                }
                "applyBatch" -> applyBatch(a)
                "call" -> call(a)
                else -> try {
                    method.invoke(delegateWrapper, *a)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun applyBatch(a: Array<out Any>): Array<ContentProviderResult> {
            val authority = a[0] as String
            val operations = a[1] as ArrayList<ContentProviderOperation>
            if (authority in guestProviderAuthorities) {
                val uriField = ContentProviderOperation::class.java.getDeclaredField("mUri")
                    .apply { isAccessible = true }
                val originalUris = operations.map { it.uri }
                return try {
                    operations.forEach { operation ->
                        uriField.set(operation, routeGuestProviderUri(operation.uri))
                    }
                    val stubAuthority = "com.privvy.app.stub.provider.v$spaceId"
                    realResolver.applyBatch(stubAuthority, operations)
                        .map { result ->
                            result.uri?.takeIf { it.authority == stubAuthority }
                                ?.let { ContentProviderResult(originalProviderUri(it)) }
                                ?: result
                        }.toTypedArray()
                } finally {
                    operations.forEachIndexed { index, operation ->
                        uriField.set(operation, originalUris[index])
                    }
                }
            }
            if (operations.any { operation ->
                    val uri = operation.uri
                    VirtualMediaStore.isExternalMediaStoreUri(uri) ||
                        VirtualMediaStore.isVirtualMediaUri(uri)
                }
            ) {
                throw UnsupportedOperationException("Virtual MediaStore does not support applyBatch")
            }
            return realResolver.applyBatch(authority, operations)
        }

        private fun routeGuestProviderUri(uri: Uri): Uri {
            val authority = requireNotNull(uri.authority) { "Guest provider URI has no authority" }
            require(authority in guestProviderAuthorities) { "Batch mixes guest provider authorities" }
            val builder = Uri.Builder()
                .scheme("content")
                .authority("com.privvy.app.stub.provider.v$spaceId")
                .appendPath(encode(guestPackageName))
                .appendPath(encode(authority))
            uri.pathSegments.forEach(builder::appendPath)
            return builder.query(uri.encodedQuery).fragment(uri.encodedFragment).build()
        }

        private fun originalProviderUri(uri: Uri): Uri {
            val segments = uri.pathSegments
            require(segments.size >= 2) { "Invalid guest provider result URI" }
            val builder = Uri.Builder()
                .scheme("content")
                .authority(decode(segments[1]))
            segments.drop(2).forEach(builder::appendPath)
            return builder.query(uri.encodedQuery).fragment(uri.encodedFragment).build()
        }

        private fun insert(a: Array<out Any>): Uri? {
            val uri = a[0] as Uri
            val target = targetFor(uri) ?: return when (a.size) {
                2 -> realResolver.insert(uri, a[1] as? ContentValues)
                else -> realResolver.insert(uri, a.getOrNull(1) as? ContentValues, a.getOrNull(2) as? Bundle)
            }
            require(target.mediaId == null) { "Media insert requires a collection URI: $uri" }
            val values = requireNotNull(a.getOrNull(1) as? ContentValues) {
                "Media insert values cannot be null"
            }
            return VirtualMediaStore.insertInternal(
                appContext,
                target.spaceId,
                values,
                guestPackageName,
                mediaTypeFor(uri),
            )
        }

        private fun bulkInsert(a: Array<out Any>): Int {
            val uri = a[0] as Uri
            val target = targetFor(uri) ?: return realResolver.bulkInsert(
                uri,
                @Suppress("UNCHECKED_CAST") (a[1] as Array<ContentValues>),
            )
            require(target.mediaId == null) { "Media insert requires a collection URI: $uri" }
            val values = @Suppress("UNCHECKED_CAST") (a[1] as Array<ContentValues>)
            var count = 0
            values.forEach { value ->
                VirtualMediaStore.insertInternal(
                    appContext,
                    target.spaceId,
                    value,
                    guestPackageName,
                    mediaTypeFor(uri),
                )
                count++
            }
            return count
        }

        private fun query(a: Array<out Any>): Cursor? {
            val uri = a[0] as Uri
            @Suppress("UNCHECKED_CAST")
            val projection = a.getOrNull(1) as? Array<out String>
            val signal = a.getOrNull(if (a.size >= 6) 5 else 3) as? CancellationSignal
            signal?.throwIfCanceled()
            if (a.size >= 6) {
                val selection = a[2] as? String
                @Suppress("UNCHECKED_CAST")
                val selectionArgs = a[3] as? Array<out String>
                val sortOrder = a[4] as? String
                val target = targetFor(uri) ?: return realResolver.query(
                    uri, projection, selection, selectionArgs, sortOrder, signal,
                )
                val result = VirtualMediaStore.queryInternal(
                    context = appContext,
                    spaceId = target.spaceId,
                    mediaId = target.mediaId,
                    projection = projection,
                    selection = selection,
                    selectionArgs = selectionArgs,
                    sortOrder = sortOrder,
                    mediaType = mediaTypeFor(uri),
                    limit = null,
                    offset = 0,
                )
                signal?.throwIfCanceled()
                return result
            }
            val extras = a.getOrNull(2) as? Bundle
            val target = targetFor(uri) ?: return realResolver.query(uri, projection, extras, signal)
            val sql = SqlArguments(extras)
            val result = VirtualMediaStore.queryInternal(
                context = appContext,
                spaceId = target.spaceId,
                mediaId = target.mediaId,
                projection = projection,
                selection = sql.selection,
                selectionArgs = sql.selectionArgs,
                sortOrder = sql.sortOrder ?: structuredSortOrder(extras),
                mediaType = mediaTypeFor(uri),
                limit = sql.limit,
                offset = sql.offset,
            )
            signal?.throwIfCanceled()
            return result
        }

        private fun delete(a: Array<out Any>): Int {
            val uri = a[0] as Uri
            val legacy = a.size >= 3
            @Suppress("UNCHECKED_CAST")
            val selectionArgs = if (legacy) a[2] as? Array<out String> else null
            val selection = if (legacy) a[1] as? String else null
            val sql = if (legacy) null else SqlArguments(a.getOrNull(1) as? Bundle)
            val target = targetFor(uri) ?: return if (legacy) {
                realResolver.delete(uri, selection, selectionArgs)
            } else {
                realResolver.delete(uri, a.getOrNull(1) as? Bundle)
            }
            return VirtualMediaStore.deleteInternal(
                appContext,
                target.spaceId,
                target.mediaId,
                selection ?: sql?.selection,
                selectionArgs ?: sql?.selectionArgs,
                mediaTypeFor(uri),
            )
        }

        private fun update(a: Array<out Any>): Int {
            val uri = a[0] as Uri
            val legacy = a.size >= 4
            @Suppress("UNCHECKED_CAST")
            val selectionArgs = if (legacy) a[3] as? Array<out String> else null
            val selection = if (legacy) a[2] as? String else null
            val sql = if (legacy) null else SqlArguments(a.getOrNull(2) as? Bundle)
            val values = requireNotNull(a.getOrNull(1) as? ContentValues) {
                "Media update values cannot be null"
            }
            val target = targetFor(uri) ?: return if (legacy) {
                realResolver.update(uri, values, selection, selectionArgs)
            } else {
                realResolver.update(uri, values, a.getOrNull(2) as? Bundle)
            }
            return VirtualMediaStore.updateInternal(
                appContext,
                target.spaceId,
                target.mediaId,
                values,
                selection ?: sql?.selection,
                selectionArgs ?: sql?.selectionArgs,
                mediaTypeFor(uri),
            )
        }

        private fun openFile(a: Array<out Any>): ParcelFileDescriptor? {
            val uri = a[0] as Uri
            val mode = a[1] as String
            val signal = a.getOrNull(2) as? CancellationSignal
            signal?.throwIfCanceled()
            val target = targetFor(uri) ?: return realResolver.openFile(uri, mode, signal)
            val mediaId = requireNotNull(target.mediaId) { "openFile requires an item URI: $uri" }
            return VirtualMediaStore.openFile(appContext, target.spaceId, mediaId, mode)
        }

        private fun openAssetFile(a: Array<out Any>): AssetFileDescriptor? {
            val uri = a[0] as Uri
            val mode = a[1] as String
            val signal = a.getOrNull(2) as? CancellationSignal
            signal?.throwIfCanceled()
            val target = targetFor(uri) ?: return realResolver.openAssetFile(uri, mode, signal)
            val mediaId = requireNotNull(target.mediaId) { "openAssetFile requires an item URI: $uri" }
            val descriptor = VirtualMediaStore.openFile(appContext, target.spaceId, mediaId, mode)
            return try {
                AssetFileDescriptor(descriptor, 0L, descriptor.statSize)
            } catch (t: Throwable) {
                descriptor.close()
                throw t
            }
        }

        private fun openTypedAssetFile(a: Array<out Any>): AssetFileDescriptor? {
            val uri = a[0] as Uri
            val mimeFilter = a[1] as String
            val signal = a.getOrNull(3) as? CancellationSignal
            signal?.throwIfCanceled()
            val target = targetFor(uri) ?: return realResolver.openTypedAssetFile(
                uri,
                mimeFilter,
                a.getOrNull(2) as? Bundle,
                signal,
            )
            val mediaId = requireNotNull(target.mediaId) { "openTypedAssetFile requires an item URI: $uri" }
            val mimeType = VirtualMediaStore.mimeType(appContext, target.spaceId, mediaId)
                ?: throw FileNotFoundException("Unknown virtual media id $mediaId")
            if (!matchesMimeFilter(mimeType, mimeFilter)) {
                throw FileNotFoundException("Media MIME type $mimeType does not match $mimeFilter")
            }
            val descriptor = VirtualMediaStore.openFile(appContext, target.spaceId, mediaId, "r")
            return try {
                AssetFileDescriptor(descriptor, 0L, descriptor.statSize)
            } catch (t: Throwable) {
                descriptor.close()
                throw t
            }
        }

        private fun getType(uri: Uri): String? {
            val target = targetFor(uri) ?: return realResolver.getType(uri)
            return if (target.mediaId == null) VirtualMediaStore.COLLECTION_MIME_TYPE
            else VirtualMediaStore.mimeType(appContext, target.spaceId, target.mediaId)
        }

        private fun getStreamTypes(uri: Uri, mimeFilter: String): Array<String>? {
            val target = targetFor(uri) ?: return realResolver.getStreamTypes(uri, mimeFilter)
            val mediaId = target.mediaId ?: return arrayOf("*/*")
            val mimeType = VirtualMediaStore.mimeType(appContext, target.spaceId, mediaId)
                ?: return null
            return if (matchesMimeFilter(mimeType, mimeFilter)) arrayOf(mimeType) else null
        }

        private fun call(a: Array<out Any?>): Bundle? {
            val authority = a[0] as String
            val extras = Bundle(a.getOrNull(3) as? Bundle)
            return if (authority in guestProviderAuthorities) {
                extras.putString("privvy.guest.package", guestPackageName)
                extras.putString("privvy.guest.authority", authority)
                realResolver.call(
                    "com.privvy.app.stub.provider.v$spaceId",
                    a[1] as String,
                    a.getOrNull(2) as? String,
                    extras,
                )
            } else {
                realResolver.call(authority, a[1] as String, a.getOrNull(2) as? String, extras)
            }
        }

        private fun guestProviderArguments(method: String, args: Array<out Any?>): Array<out Any?>? {
            if (method in setOf("applyBatch", "call", "canonicalize", "uncanonicalize")) return null
            val uri = args.getOrNull(0) as? Uri ?: return null
            val authority = uri.authority ?: return null
            if (authority !in guestProviderAuthorities ||
                VirtualMediaStore.isExternalMediaStoreUri(uri) ||
                VirtualMediaStore.isVirtualMediaUri(uri)
            ) {
                return null
            }
            val routed = routeGuestProviderUri(uri)
            return Array(args.size) { args[it] }.also { it[0] = routed }
        }

        private fun encode(value: String): String =
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

        private fun decode(value: String): String =
            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)

        private fun targetFor(uri: Uri): VirtualMediaStore.VirtualTarget? {
            if (VirtualMediaStore.isExternalMediaStoreUri(uri)) {
                return VirtualMediaStore.VirtualTarget(
                    spaceId,
                    VirtualMediaStore.externalMediaId(uri),
                )
            }
            if (VirtualMediaStore.isVirtualMediaUri(uri)) {
                val target = VirtualMediaStore.parseVirtualUri(uri)
                require(target.spaceId == spaceId) {
                    "Guest in space $spaceId cannot access virtual space ${target.spaceId}"
                }
                return target
            }
            return null
        }

        private fun mediaTypeFor(uri: Uri): String? =
            if (VirtualMediaStore.isExternalMediaStoreUri(uri)) {
                VirtualMediaStore.externalMediaType(uri)
            } else null

        private fun matchesMimeFilter(mimeType: String, filter: String): Boolean {
            val value = filter.trim().lowercase(Locale.ROOT)
            return value == "*/*" || VirtualMediaStore.mimeTypeMatches(mimeType, value)
        }

        private fun structuredSortOrder(extras: Bundle?): String? {
            val columns = extras?.getStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS)
                ?: return null
            if (columns.isEmpty()) return null
            val descending = extras.getInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            ) == ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            return columns.joinToString(", ") { column ->
                column + if (descending) " DESC" else " ASC"
            }
        }
    }

    private class SqlArguments(extras: Bundle?) {
        val selection: String? = extras?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
        val selectionArgs: Array<out String>? = extras?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        val sortOrder: String? = extras?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        val limit: Int? = extras?.getString(ContentResolver.QUERY_ARG_SQL_LIMIT)?.toNonNegativeInt()
        val offset: Int = extras?.getString(ContentResolver.QUERY_ARG_OFFSET)?.toNonNegativeInt() ?: 0

        private fun Any.toNonNegativeInt(): Int {
            val value = when (this) {
                is Number -> toInt()
                else -> toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid SQL query argument: $this")
            }
            require(value >= 0) { "SQL query arguments cannot be negative" }
            return value
        }
    }
}
