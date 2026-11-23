package com.privvy.app.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Minimal Android binary XML (AXML) reader for AndroidManifest.xml.
 *
 * We only need the package name, the MAIN/LAUNCHER activity, and its theme.
 * Using a parser instead of hidden PackageParser APIs keeps this stable across
 * Android 11–16.
 */
object ApkManifestParser {

    data class LaunchInfo(val activityName: String, val themeResId: Int)

    fun parse(apk: File, fallbackPackageName: String? = null): LaunchInfo? {
        val bytes = try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            Parser(bytes).parse(fallbackPackageName)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Heuristic fallback when AXML parsing fails. Prefers a main/launcher-named
     * exported activity, then any exported activity, then anything.
     */
    fun fallbackLaunchActivity(activities: Array<android.content.pm.ActivityInfo>?): String? {
        if (activities.isNullOrEmpty()) return null
        val exported = activities.filter { it.exported }
        val preferred = exported.firstOrNull {
            it.name.endsWith("MainActivity") || it.name.contains("Launcher") || it.name.endsWith(".Main")
        }
        return preferred?.name
            ?: exported.firstOrNull()?.name
            ?: activities.firstOrNull()?.name
    }

    private class Parser(private val data: ByteArray) {

        private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private var strings: Array<String> = emptyArray()

        fun parse(fallbackPackageName: String?): LaunchInfo? {
            var offset = 8 // skip the top-level RES_XML_TYPE chunk header
            var packageName = fallbackPackageName
            var applicationTheme = 0

            var currentActivity: String? = null
            var currentAliasTarget: String? = null
            var currentTheme = 0
            var inFilter = false
            var hasMain = false
            var hasLauncher = false
            var result: LaunchInfo? = null

            while (offset + 8 <= data.size) {
                val type = u16(offset)
                val size = u32(offset + 4)
                if (size < 8 || offset + size > data.size) break

                when (type) {
                    0x0001 -> strings = parseStringPool(offset) // RES_STRING_POOL_TYPE

                    0x0102 -> { // RES_XML_START_ELEMENT_TYPE
                        val element = strings.getOrNull(u32(offset + 20)) ?: ""
                        val attrs = attributes(offset)
                        when (element) {
                            "manifest" -> attrString(attrs, "package")?.let { packageName = it }

                            "application" -> {
                                applicationTheme = attrs.firstOrNull { it.name == "theme" }
                                    ?.referenceValue() ?: 0
                            }

                            "activity" -> {
                                currentActivity = attrString(attrs, "name")
                                currentAliasTarget = null
                                currentTheme = attrs.firstOrNull { it.name == "theme" }
                                    ?.referenceValue() ?: 0
                                inFilter = false; hasMain = false; hasLauncher = false
                            }

                            "activity-alias" -> {
                                currentActivity = attrString(attrs, "name")
                                currentAliasTarget = attrString(attrs, "targetActivity")
                                currentTheme = attrs.firstOrNull { it.name == "theme" }
                                    ?.referenceValue() ?: 0
                                inFilter = false; hasMain = false; hasLauncher = false
                            }

                            "intent-filter" -> {
                                inFilter = true; hasMain = false; hasLauncher = false
                            }

                            "action" -> {
                                if (inFilter &&
                                    attrString(attrs, "name") == "android.intent.action.MAIN"
                                ) hasMain = true
                            }

                            "category" -> {
                                if (inFilter &&
                                    attrString(attrs, "name") == "android.intent.category.LAUNCHER"
                                ) hasLauncher = true
                            }
                        }
                    }

                    0x0103 -> { // RES_XML_END_ELEMENT_TYPE
                        val element = strings.getOrNull(u32(offset + 16)) ?: ""
                        when (element) {
                            "intent-filter" -> {
                                if (result == null && inFilter && hasMain && hasLauncher) {
                                    val activity = currentAliasTarget ?: currentActivity
                                    if (!activity.isNullOrEmpty() && !packageName.isNullOrEmpty()) {
                                        result = LaunchInfo(
                                            activityName = resolve(activity, packageName),
                                            themeResId = if (currentTheme != 0) currentTheme else applicationTheme,
                                        )
                                    }
                                }
                                inFilter = false
                            }

                            "activity", "activity-alias" -> {
                                currentActivity = null
                                currentAliasTarget = null
                                currentTheme = 0
                                inFilter = false; hasMain = false; hasLauncher = false
                            }
                        }
                    }
                }
                offset += size
            }
            return result
        }

        // ---------------------------------------------------------------
        // Chunk readers
        // ---------------------------------------------------------------

        private fun parseStringPool(chunkStart: Int): Array<String> {
            val count = u32(chunkStart + 8)
            val flags = u32(chunkStart + 16)
            val stringsStart = u32(chunkStart + 20)
            val offsetsBase = chunkStart + 28 // standard ResStringPool_header size
            val utf8 = (flags and 0x100) != 0
            val out = Array(count) { "" }
            for (i in 0 until count) {
                val relative = u32(offsetsBase + i * 4)
                val pos = chunkStart + stringsStart + relative
                out[i] = if (utf8) readUtf8(pos) else readUtf16(pos)
            }
            return out
        }

        private fun readUtf8(pos: Int): String {
            if (pos < 0 || pos >= data.size) return ""
            val first = readLength8(pos)
            val second = readLength8(first.second)
            val byteCount = second.first
            val start = second.second
            if (start < 0 || byteCount < 0 || start + byteCount > data.size) return ""
            return String(data, start, byteCount, Charsets.UTF_8)
        }

        private fun readUtf16(pos: Int): String {
            if (pos < 0 || pos + 2 > data.size) return ""
            var p = pos
            var len = u16(p); p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7fff) shl 16) or u16(p)
                p += 2
            }
            val byteCount = len * 2
            if (byteCount < 0 || p + byteCount > data.size) return ""
            val bytes = data.copyOfRange(p, p + byteCount)
            return String(bytes, Charsets.UTF_16LE)
        }

        private fun readLength8(pos: Int): Pair<Int, Int> {
            var p = pos
            var value = data[p].toInt() and 0xff
            p++
            if (value and 0x80 != 0) {
                value = ((value and 0x7f) shl 8) or (data[p].toInt() and 0xff)
                p++
            }
            return value to p
        }

        private fun attributes(startElementOffset: Int): List<Attr> {
            val attributeStart = u16(startElementOffset + 24)
            val attributeSize = u16(startElementOffset + 26)
            val attributeCount = u16(startElementOffset + 28)
            if (attributeCount == 0) return emptyList()
            val base = startElementOffset + 16 + attributeStart
            val out = ArrayList<Attr>(attributeCount)
            for (i in 0 until attributeCount) {
                val p = base + i * attributeSize
                if (p + 20 > data.size) break
                val nameIdx = u32(p + 4)
                val rawValue = u32(p + 8)
                val dataType = data[p + 15].toInt() and 0xff
                val value = u32(p + 16)
                val name = strings.getOrNull(nameIdx) ?: ""
                out.add(Attr(name, rawValue, dataType, value))
            }
            return out
        }

        private fun attrString(attrs: List<Attr>, name: String): String? {
            val attr = attrs.firstOrNull { it.name == name } ?: return null
            if (attr.rawValue >= 0) {
                strings.getOrNull(attr.rawValue)?.let { return it }
            }
            if (attr.dataType == 0x03) {
                strings.getOrNull(attr.data)?.let { return it }
            }
            return null
        }

        private fun u16(offset: Int): Int =
            buf.getShort(offset).toInt() and 0xffff

        private fun u32(offset: Int): Int =
            buf.getInt(offset)

        private fun resolve(name: String, pkg: String): String = when {
            name.startsWith(".") -> pkg + name
            !name.contains('.') -> "$pkg.$name"
            else -> name
        }
    }

    private data class Attr(
        val name: String,
        val rawValue: Int,
        val dataType: Int,
        val data: Int,
    ) {
        fun referenceValue(): Int? =
            if (dataType == 0x01 || dataType == 0x02) data else null
    }
}
