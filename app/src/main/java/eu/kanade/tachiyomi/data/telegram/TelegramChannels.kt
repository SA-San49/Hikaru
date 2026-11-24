package eu.kanade.tachiyomi.data.telegram

import eu.kanade.domain.telegram.TelegramPreferences
import org.json.JSONArray
import org.json.JSONObject

object TelegramChannels {

    fun decode(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val handle = obj.optString("handle").trim()
                if (handle.isBlank()) return@mapNotNull null
                handle to obj.optString("title").trim()
            }
        }.getOrDefault(emptyList())
    }

    fun encode(list: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((handle, title) in list) {
            arr.put(JSONObject().put("handle", handle).put("title", title))
        }
        return arr.toString()
    }

    fun add(prefs: TelegramPreferences, existing: List<Pair<String, String>>, handle: String, title: String) {
        if (existing.any { it.first.equals(handle, ignoreCase = true) }) return
        prefs.channels().set(encode(existing + (handle to title)))
    }

    fun remove(prefs: TelegramPreferences, existing: List<Pair<String, String>>, handle: String) {
        prefs.channels().set(encode(existing.filterNot { it.first.equals(handle, ignoreCase = true) }))
    }

    fun setTitle(prefs: TelegramPreferences, existing: List<Pair<String, String>>, handle: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val updated = existing.map { (h, t) ->
            if (h.equals(handle, ignoreCase = true)) h to clean else h to t
        }
        if (updated != existing) prefs.channels().set(encode(updated))
    }
}
