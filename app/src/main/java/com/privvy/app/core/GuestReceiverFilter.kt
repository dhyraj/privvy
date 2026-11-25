package com.privvy.app.core

import org.json.JSONArray
import org.json.JSONObject

/** Manifest intent-filter data for a guest BroadcastReceiver. */
data class GuestReceiverFilter(
    val receiverName: String,
    val exported: Boolean = false,
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList(),
    val dataHosts: List<String> = emptyList(),
    val dataPaths: List<String> = emptyList(),
    val dataMimeTypes: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("receiverName", receiverName)
        put("exported", exported)
        put("actions", JSONArray(actions))
        put("categories", JSONArray(categories))
        put("dataSchemes", JSONArray(dataSchemes))
        put("dataHosts", JSONArray(dataHosts))
        put("dataPaths", JSONArray(dataPaths))
        put("dataMimeTypes", JSONArray(dataMimeTypes))
    }

    companion object {
        fun fromJson(json: JSONObject): GuestReceiverFilter? {
            val name = json.optString("receiverName").takeIf { it.isNotEmpty() } ?: return null
            return GuestReceiverFilter(
                receiverName = name,
                exported = json.optBoolean("exported", false),
                actions = json.stringList("actions"),
                categories = json.stringList("categories"),
                dataSchemes = json.stringList("dataSchemes"),
                dataHosts = json.stringList("dataHosts"),
                dataPaths = json.stringList("dataPaths"),
                dataMimeTypes = json.stringList("dataMimeTypes"),
            )
        }

        private fun JSONObject.stringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
    }
}