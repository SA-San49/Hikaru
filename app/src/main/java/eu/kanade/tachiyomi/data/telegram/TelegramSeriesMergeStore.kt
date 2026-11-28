package eu.kanade.tachiyomi.data.telegram

import android.net.Uri
import eu.kanade.domain.telegram.TelegramPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.anime.model.Anime

object TelegramSeriesMergeStore {

    data class MergedSeriesRule(
        val targetKey: String,
        val targetTitle: String,
        val targetUrl: String,
        val memberKeys: List<String>,
        val memberTitles: List<String>,
        val orderedKeys: List<String> = listOf(targetKey) + memberKeys,
        val excludedMessageIds: Set<Long> = emptySet(),
    )

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedBySource: Map<Long, List<MergedSeriesRule>> = emptyMap()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun extractSeriesKey(url: String): String {
        if (url.startsWith("/tg/series")) {
            val parsed = Uri.parse("https://t.me$url").getQueryParameter("key").orEmpty()
            if (parsed.isNotBlank()) return parsed
        }
        return "url:$url"
    }

    fun isUnmergedEntryUrl(url: String): Boolean {
        if (!url.startsWith("/tg/series")) return false
        return Uri.parse("https://t.me$url").getQueryParameter("unmerged") == "1"
    }

    fun hasMergeForUrl(prefs: TelegramPreferences, sourceId: Long, url: String): Boolean {
        if (!url.startsWith("/tg/series") || isUnmergedEntryUrl(url)) return false
        val key = extractSeriesKey(url)
        return ruleForKey(prefs, sourceId, key) != null
    }

    fun rulesForSource(prefs: TelegramPreferences, sourceId: Long): List<MergedSeriesRule> {
        val raw = prefs.mergedSeries().get()
        return getOrParse(raw)[sourceId].orEmpty()
    }

    fun ruleForKey(prefs: TelegramPreferences, sourceId: Long, key: String): MergedSeriesRule? {
        if (key.isBlank()) return null
        return rulesForSource(prefs, sourceId).firstOrNull { rule ->
            rule.targetKey == key || key in rule.memberKeys
        }
    }

    fun selectionSlotOf(
        rule: MergedSeriesRule,
        groupKey: String,
        altKeys: Collection<String> = emptyList(),
    ): Int? {
        val order = rule.orderedKeys.ifEmpty { listOf(rule.targetKey) + rule.memberKeys }
        val directIdx = order.indexOf(groupKey)
        if (directIdx >= 0) return directIdx
        for (alt in altKeys) {
            val altIdx = order.indexOf(alt)
            if (altIdx >= 0) return altIdx
        }
        return null
    }

    @Synchronized
    fun mergeSeries(
        prefs: TelegramPreferences,
        sourceId: Long,
        selectedAnimes: List<Anime>,
        extraAliasesByKey: Map<String, Set<String>> = emptyMap(),
    ): MergedSeriesRule? {
        if (selectedAnimes.size < 2) return null
        val primary = selectedAnimes.first()
        val targetKey = extractSeriesKey(primary.url)
        val currentAll = getOrParse(prefs.mergedSeries().get()).toMutableMap()
        val currentForSource = currentAll[sourceId].orEmpty()

        val selectedKeys = collectSelectedKeys(selectedAnimes, extraAliasesByKey)
        val overlappingRules = currentForSource.filter { rule ->
            rule.targetKey in selectedKeys || rule.memberKeys.any { it in selectedKeys }
        }

        val newRule = buildMergedRule(
            targetKey = targetKey,
            targetTitle = primary.title,
            targetUrl = primary.url,
            selectedAnimes = selectedAnimes,
            extraAliasesByKey = extraAliasesByKey,
            overlappingRules = overlappingRules,
        )

        currentAll[sourceId] = currentForSource.filterNot { it in overlappingRules } + newRule
        persistAll(prefs, currentAll)
        return newRule
    }

    @Synchronized
    @Suppress("LongParameterList", "ReturnCount")
    fun unmergeEpisodes(
        prefs: TelegramPreferences,
        sourceId: Long,
        seriesKey: String,
        messageIds: Set<Long>,
        unmergedMemberKeys: Set<String> = emptySet(),
    ): Boolean {
        if (seriesKey.isBlank() || messageIds.isEmpty()) return false
        val currentAll = getOrParse(prefs.mergedSeries().get()).toMutableMap()
        val currentForSource = currentAll[sourceId].orEmpty()
        val rule = currentForSource.firstOrNull {
            it.targetKey == seriesKey || seriesKey in it.memberKeys
        } ?: return false

        val dropKeys = unmergedMemberKeys.filterTo(HashSet()) { it != rule.targetKey }
        val remainingMembers = rule.memberKeys.filterNot { it in dropKeys }
        val remainingOrder = rule.orderedKeys.filterNot { it in dropKeys }
        val updatedExcluded = (rule.excludedMessageIds + messageIds).toSet()

        val dissolve = remainingMembers.isEmpty() && dropKeys.isNotEmpty()
        val nextRules = if (dissolve) {
            currentForSource.filterNot { it == rule }
        } else {
            val updatedRule = rule.copy(
                memberKeys = remainingMembers,
                orderedKeys = remainingOrder.ifEmpty { listOf(rule.targetKey) + remainingMembers },
                excludedMessageIds = updatedExcluded,
            )
            currentForSource.map { if (it == rule) updatedRule else it }
        }

        if (nextRules.isEmpty()) {
            currentAll.remove(sourceId)
        } else {
            currentAll[sourceId] = nextRules
        }
        persistAll(prefs, currentAll)
        return true
    }

    private fun persistAll(
        prefs: TelegramPreferences,
        currentAll: Map<Long, List<MergedSeriesRule>>,
    ) {
        val encoded = encode(currentAll)
        cachedRaw = encoded
        cachedBySource = currentAll
        prefs.mergedSeries().set(encoded)
        _revision.value = _revision.value + 1L
    }

    private fun collectSelectedKeys(
        selectedAnimes: List<Anime>,
        extraAliasesByKey: Map<String, Set<String>>,
    ): Set<String> = linkedSetOf<String>().apply {
        selectedAnimes.forEach { anime ->
            val key = extractSeriesKey(anime.url)
            add(key)
            extraAliasesByKey[key]?.let(::addAll)
        }
    }

    @Suppress("LongParameterList")
    private fun buildMergedRule(
        targetKey: String,
        targetTitle: String,
        targetUrl: String,
        selectedAnimes: List<Anime>,
        extraAliasesByKey: Map<String, Set<String>>,
        overlappingRules: List<MergedSeriesRule>,
    ): MergedSeriesRule {
        val combinedKeys = linkedSetOf<String>()
        val combinedTitles = linkedSetOf<String>()
        val ordered = linkedSetOf<String>()

        selectedAnimes.forEachIndexed { idx, anime ->
            val k = extractSeriesKey(anime.url)
            val existingRule = overlappingRules.firstOrNull { it.targetKey == k || k in it.memberKeys }
            if (existingRule != null) {
                existingRule.orderedKeys.forEach { ordered += it }
            }
            ordered += k
            if (k != targetKey) combinedKeys += k
            extraAliasesByKey[k]?.filterTo(combinedKeys) { it != targetKey }
            if (idx > 0 && anime.title.isNotBlank() && anime.title != targetTitle) {
                combinedTitles += anime.title
            }
        }
        overlappingRules.forEach { oldRule ->
            if (oldRule.targetKey != targetKey) combinedKeys += oldRule.targetKey
            oldRule.memberKeys.filterTo(combinedKeys) { it != targetKey }
            oldRule.orderedKeys.forEach { ordered += it }
            if (oldRule.targetTitle.isNotBlank() && oldRule.targetTitle != targetTitle) {
                combinedTitles += oldRule.targetTitle
            }
            oldRule.memberTitles.filterTo(combinedTitles) { it.isNotBlank() && it != targetTitle }
        }
        return MergedSeriesRule(
            targetKey = targetKey,
            targetTitle = targetTitle,
            targetUrl = targetUrl,
            memberKeys = combinedKeys.toList(),
            memberTitles = combinedTitles.toList(),
            orderedKeys = ordered.toList(),
            excludedMessageIds = emptySet(),
        )
    }

    private fun getOrParse(raw: String): Map<Long, List<MergedSeriesRule>> {
        if (raw == cachedRaw) return cachedBySource
        return synchronized(this) {
            if (raw == cachedRaw) return@synchronized cachedBySource
            val parsed = parse(raw)
            cachedRaw = raw
            cachedBySource = parsed
            parsed
        }
    }

    private fun parse(raw: String): Map<Long, List<MergedSeriesRule>> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val result = HashMap<Long, List<MergedSeriesRule>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val srcKey = keys.next()
                val sourceId = srcKey.toLongOrNull() ?: continue
                val rules = parseSourceRules(root.optJSONArray(srcKey))
                if (rules.isNotEmpty()) result[sourceId] = rules
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun parseSourceRules(arr: JSONArray?): List<MergedSeriesRule> {
        if (arr == null) return emptyList()
        val rules = ArrayList<MergedSeriesRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val targetKey = obj.optString("tk")
            val targetTitle = obj.optString("tt")
            if (targetKey.isBlank() || targetTitle.isBlank()) continue
            val memberKeys = readStringList(obj.optJSONArray("mk"))
            val orderedKeys = readStringList(obj.optJSONArray("ok"))
                .ifEmpty { listOf(targetKey) + memberKeys }
            val excludedIds = readLongSet(obj.optJSONArray("ex"))
            rules += MergedSeriesRule(
                targetKey = targetKey,
                targetTitle = targetTitle,
                targetUrl = obj.optString("tu"),
                memberKeys = memberKeys,
                memberTitles = readStringList(obj.optJSONArray("mt")),
                orderedKeys = orderedKeys,
                excludedMessageIds = excludedIds,
            )
        }
        return rules
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) add(v)
            }
        }
    }

    private fun readLongSet(arr: JSONArray?): Set<Long> {
        if (arr == null) return emptySet()
        return buildSet(arr.length()) {
            for (i in 0 until arr.length()) {
                val v = arr.optLong(i, 0L)
                if (v != 0L) add(v)
            }
        }
    }

    private fun encode(map: Map<Long, List<MergedSeriesRule>>): String {
        val root = JSONObject()
        map.forEach { (sourceId, rules) ->
            if (rules.isEmpty()) return@forEach
            val arr = JSONArray()
            rules.forEach { rule ->
                val obj = JSONObject()
                obj.put("tk", rule.targetKey)
                obj.put("tt", rule.targetTitle)
                obj.put("tu", rule.targetUrl)
                obj.put("mk", JSONArray(rule.memberKeys))
                obj.put("mt", JSONArray(rule.memberTitles))
                if (rule.orderedKeys.isNotEmpty()) {
                    obj.put("ok", JSONArray(rule.orderedKeys))
                }
                if (rule.excludedMessageIds.isNotEmpty()) {
                    obj.put("ex", JSONArray(rule.excludedMessageIds.toList()))
                }
                arr.put(obj)
            }
            root.put(sourceId.toString(), arr)
        }
        return root.toString()
    }
}
