package io.github.renzodambrosi.distilla.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A story opened in Distilla. [title] is empty until the page has been read. */
data class HistoryEntry(val url: String, val title: String = "", val time: Long) {
    val host: String get() = UrlMatcher.hostOf(url)?.removePrefix("www.") ?: url
}

/**
 * Reading history, most recent first. Pure functions: the app stores the list,
 * these decide its content.
 */
object History {

    const val MAX = 200

    private fun same(a: String, b: String) = normalize(a) == normalize(b)

    /** Same page with or without the trailing slash or the "#fragment". */
    private fun normalize(url: String) = url.trim().substringBefore('#').trimEnd('/').lowercase()

    /**
     * Puts [url] at the top. Opening it again moves it up instead of duplicating it;
     * an empty [title] keeps the title already known.
     */
    fun add(list: List<HistoryEntry>, url: String, title: String, time: Long, max: Int = MAX): List<HistoryEntry> {
        val old = list.firstOrNull { same(it.url, url) }
        val entry = HistoryEntry(url.trim(), title.trim().ifBlank { old?.title.orEmpty() }, time)
        return (listOf(entry) + list.filterNot { same(it.url, url) }).take(max)
    }

    /** Sets the title once the page has been read, without changing the order. */
    fun setTitle(list: List<HistoryEntry>, url: String, title: String): List<HistoryEntry> =
        if (title.isBlank()) list else list.map { if (same(it.url, url)) it.copy(title = title.trim()) else it }

    fun remove(list: List<HistoryEntry>, url: String): List<HistoryEntry> = list.filterNot { same(it.url, url) }

    /**
     * The page turned out to live at [newUrl] (shortened link resolved, or redirect):
     * the entry takes the final address, keeping its place; a duplicate of [newUrl] is dropped.
     */
    fun replaceUrl(list: List<HistoryEntry>, oldUrl: String, newUrl: String): List<HistoryEntry> {
        if (same(oldUrl, newUrl) || list.none { same(it.url, oldUrl) }) return list
        return list.filterNot { same(it.url, newUrl) }
            .map { if (same(it.url, oldUrl)) it.copy(url = newUrl.trim()) else it }
    }

    fun toJson(list: List<HistoryEntry>): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonArray {
            list.forEach { e ->
                add(buildJsonObject {
                    put("url", e.url)
                    put("title", e.title)
                    put("time", e.time)
                })
            }
        },
    )

    fun fromJson(text: String): List<HistoryEntry> {
        val arr = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HistoryEntry(
                url = url,
                title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                time = (o["time"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }.sortedByDescending { it.time }
    }
}
