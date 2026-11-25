package com.privvy.app.engine

import android.content.SharedPreferences
import com.privvy.app.core.VirtualStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class GuestSharedPreferences(
    private val file: File,
    private val listeners: MutableSet<SharedPreferences.OnSharedPreferenceChangeListener>,
) : SharedPreferences {

    private val lock = Any()
    private var values: Map<String, Any?> = load()

    override fun getAll(): Map<String, *> = synchronized(lock) { values.toMap() }

    override fun getString(key: String?, defValue: String?): String? = synchronized(lock) {
        values[key] as? String ?: defValue
    }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        (values[key] as? Set<String>)?.toSet() ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) { values[key] as? Int ?: defValue }
    override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) synchronized(listeners) { listeners.remove(listener) }
    }

    private fun load(): Map<String, Any?> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText())
            val result = LinkedHashMap<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                result[key] = when (value) {
                    is JSONArray -> buildSet { for (index in 0 until value.length()) add(value.getString(index)) }
                    else -> value
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun commit(updates: Map<String, Any?>, removed: Set<String>, clear: Boolean) {
        val changed = LinkedHashSet<String>()
        synchronized(lock) {
            val updated = if (clear) LinkedHashMap<String, Any?>() else LinkedHashMap(values)
            removed.forEach { key -> if (updated.remove(key) != null) changed.add(key) }
            updates.forEach { (key, value) ->
                if (value == null) {
                    if (updated.remove(key) != null) changed.add(key)
                } else {
                    if (updated[key] != value) changed.add(key)
                    updated[key] = value
                }
            }
            values = updated
            persist()
        }
        val snapshot = synchronized(listeners) { listeners.toSet() }
        snapshot.forEach { listener -> changed.forEach { key -> listener.onSharedPreferenceChanged(this, key) } }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        values.forEach { (key, value) ->
            when (value) {
                is Set<*> -> json.put(key, JSONArray(value.map { it.toString() }))
                else -> json.put(key, value)
            }
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(json.toString())
            temporary.delete()
        }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: Set<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = apply { if (key != null) removed.add(key) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) removed.addAll(values.keys)
            commit(updates, removed, clear)
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String?, value: Any?) = apply {
            if (key != null) {
                removed.remove(key)
                updates[key] = value
            }
        }
    }
}
