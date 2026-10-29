package io.github.renzodambrosi.distilla.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The Git file is not in the expected format. */
class ConfigFormatException(message: String) : IllegalArgumentException(message)

/**
 * Format of the shared Git file (sites.json):
 *
 * {
 *   "version": 1,
 *   "sites": [
 *     { "name": "ANSA", "pattern": "*.ansa.it", "xpath": "//div[@class='news-txt']" },
 *     { "name": "Red Hot Cyber", "pattern": "*.redhotcyber.com",
 *       "include": ".wp-block-paragraph, h2", "exclude": ".sharedaddy", "order": "include-first" }
 *   ]
 * }
 *
 * "xpath", "include" and "exclude" are all optional. A rule needs at least one of them,
 * or only a "userAgent" (then the text is found by automatic detection).
 * "order" is "include-first" (default) or "exclude-first".
 * "userAgent" (optional) replaces the app's User-Agent for that site.
 * "browser": true (optional) always loads that site with the browser engine (WebView).
 *
 * A plain array [ {...}, {...} ] and the key "url" instead of "pattern" are accepted too.
 */
object SitesJson {

    private val pretty = Json { prettyPrint = true }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    /** Reads the Git file. All resulting rules have source = REMOTE. */
    fun parseRemote(text: String): List<SiteRule> {
        val root = Json.parseToJsonElement(text.trim().removePrefix("﻿"))
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["sites"] as? JsonArray
                ?: throw ConfigFormatException("missing \"sites\" list")
            else -> throw ConfigFormatException("unexpected JSON root")
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pattern = o.str("pattern").ifBlank { o.str("url") }
            val rule = SiteRule(
                name = o.str("name"),
                pattern = pattern,
                xpath = o.str("xpath"),
                include = o.str("include"),
                exclude = o.str("exclude"),
                order = FilterOrder.fromJson(o.str("order")),
                userAgent = o.str("userAgent"),
                useBrowser = o.bool("browser"),
                source = RuleSource.REMOTE,
            )
            if (pattern.isEmpty() || !rule.isUsable) null else rule
        }.distinctBy { it.key }
    }

    /** The app's own file: also keeps id, origin and ignored conflicts. */
    fun toStorage(rules: List<SiteRule>): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonArray {
            rules.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("name", r.name)
                    put("pattern", r.pattern)
                    put("xpath", r.xpath)
                    put("include", r.include)
                    put("exclude", r.exclude)
                    put("order", r.order.json)
                    put("userAgent", r.userAgent)
                    put("browser", r.useBrowser)
                    put("source", r.source.name)
                    r.ignoredRemote?.let { put("ignoredRemote", it) }
                })
            }
        }
    )

    fun fromStorage(text: String): List<SiteRule> {
        val arr = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pattern = o.str("pattern")
            if (pattern.isEmpty()) return@mapNotNull null
            SiteRule(
                id = o.str("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = o.str("name"),
                pattern = pattern,
                xpath = o.str("xpath"),
                include = o.str("include"),
                exclude = o.str("exclude"),
                order = FilterOrder.fromJson(o.str("order")),
                userAgent = o.str("userAgent"),
                useBrowser = o.bool("browser"),
                source = runCatching { RuleSource.valueOf(o.str("source")) }.getOrDefault(RuleSource.USER),
                // "ignoredRemoteXpath": key used by the first version, an XPath is a valid signature
                ignoredRemote = o.str("ignoredRemote").ifBlank { o.str("ignoredRemoteXpath") }.ifBlank { null },
            ).takeIf { it.isUsable }
        }
    }

    /** Exports in sites.json format, ready to commit to the repository. */
    fun toSitesFile(rules: List<SiteRule>): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("version", 1)
            put("sites", buildJsonArray {
                rules.forEach { r ->
                    add(buildJsonObject {
                        if (r.name.isNotBlank()) put("name", r.name)
                        put("pattern", r.pattern)
                        if (r.xpath.isNotBlank()) put("xpath", r.xpath)
                        if (r.include.isNotBlank()) put("include", r.include)
                        if (r.exclude.isNotBlank()) put("exclude", r.exclude)
                        if (r.order != FilterOrder.INCLUDE_FIRST) put("order", r.order.json)
                        if (r.userAgent.isNotBlank()) put("userAgent", r.userAgent)
                        if (r.useBrowser) put("browser", true)
                    })
                }
            })
        }
    )
}
