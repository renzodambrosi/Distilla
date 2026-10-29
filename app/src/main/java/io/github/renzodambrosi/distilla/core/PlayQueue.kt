package io.github.renzodambrosi.distilla.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** A story in the listening queue. [title] may be empty until the page has been read. */
data class QueueItem(val url: String, val title: String = "") {
    val host: String get() = UrlMatcher.hostOf(url)?.removePrefix("www.") ?: url
}

/** What is saved to resume listening after the app or the phone restarts. */
data class QueueSnapshot(
    val items: List<QueueItem> = emptyList(),
    /** Story being read, or -1 when the queue is empty. */
    val current: Int = -1,
    /** Paragraph reached in the current story. */
    val chunk: Int = 0,
)

/**
 * Listening queue logic, as pure functions on lists (unit-tested). The player keeps its
 * own list of media items and uses these to stay consistent: no duplicates, and the
 * story being read stays the same one when other stories are added, moved or removed.
 */
object PlayQueue {

    /** Same page with or without the trailing slash or the "#fragment" (as in [History]). */
    fun sameUrl(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(url: String) = url.trim().substringBefore('#').trimEnd('/').lowercase()

    fun indexOf(urls: List<String>, url: String): Int = urls.indexOfFirst { sameUrl(it, url) }

    /** [new] without the stories already in [urls] and without repeats among themselves. */
    fun <T> distinctNew(urls: List<String>, new: List<T>, urlOf: (T) -> String): List<T> {
        val seen = urls.map(::norm).toMutableSet()
        return new.filter { seen.add(norm(urlOf(it))) }
    }

    /** Where the current story ends up after inserting [count] items at [at]. */
    fun currentAfterAdd(current: Int, at: Int, count: Int): Int =
        when {
            current < 0 -> if (count > 0) 0 else -1   // empty queue: the first added story becomes current
            at <= current -> current + count
            else -> current
        }

    /**
     * Where the current story ends up after removing [from] until [toExclusive].
     * If the current story itself is removed, the one that took its place becomes current
     * (or the last one if it was at the end); -1 when the queue is now empty.
     */
    fun currentAfterRemove(current: Int, from: Int, toExclusive: Int, sizeBefore: Int): Int {
        val removed = toExclusive - from
        val sizeAfter = sizeBefore - removed
        if (sizeAfter <= 0) return -1
        return when {
            current < from -> current
            current >= toExclusive -> current - removed
            else -> minOf(from, sizeAfter - 1)
        }
    }

    /** [list] with items [from, toExclusive) moved so that the first lands at [newIndex]. */
    fun <T> move(list: List<T>, from: Int, toExclusive: Int, newIndex: Int): List<T> {
        val m = list.toMutableList()
        val block = m.subList(from, toExclusive).toList()
        repeat(block.size) { m.removeAt(from) }
        m.addAll(newIndex, block)
        return m
    }

    /** Where the current story ends up after [move]. */
    fun currentAfterMove(current: Int, from: Int, toExclusive: Int, newIndex: Int, size: Int): Int {
        if (current < 0) return current
        val order = move((0 until size).toList(), from, toExclusive, newIndex)
        return order.indexOf(current)
    }

    // ------------------------------------------------------------ persistence

    fun toJson(s: QueueSnapshot): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("current", s.current)
            put("chunk", s.chunk)
            put("items", buildJsonArray {
                s.items.forEach { add(buildJsonObject { put("url", it.url); put("title", it.title) }) }
            })
        },
    )

    fun fromJson(text: String): QueueSnapshot {
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return QueueSnapshot()
        val items = runCatching { o["items"]!!.jsonArray }.getOrNull().orEmpty().mapNotNull { el ->
            val i = el as? JsonObject ?: return@mapNotNull null
            val url = (i["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QueueItem(url, (i["title"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        val current = (o["current"] as? JsonPrimitive)?.intOrNull ?: -1
        val chunk = (o["chunk"] as? JsonPrimitive)?.intOrNull ?: 0
        return if (items.isEmpty()) QueueSnapshot()
        else QueueSnapshot(items, current.coerceIn(0, items.lastIndex), chunk.coerceAtLeast(0))
    }
}
