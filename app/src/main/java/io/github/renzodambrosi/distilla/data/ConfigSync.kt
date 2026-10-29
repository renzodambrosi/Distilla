package io.github.renzodambrosi.distilla.data

import io.github.renzodambrosi.distilla.core.MergeResult
import io.github.renzodambrosi.distilla.core.SitesJson
import org.jsoup.Jsoup

class MissingRemoteUrlException : IllegalStateException("remote URL not set")

/** Downloads sites.json from the Git server and merges it into the local rules. */
class ConfigSync(private val repo: RulesRepository, private val prefs: Prefs) {

    fun sync(): MergeResult {
        val url = prefs.remoteUrl.trim()
        if (url.isEmpty()) throw MissingRemoteUrlException()

        val conn = Jsoup.connect(url)
            .ignoreContentType(true)
            .userAgent("Distilla/1.0 (Android)")
            .header("Cache-Control", "no-cache")
            .timeout(20_000)
            .maxBodySize(5 * 1024 * 1024)
        prefs.token.takeIf { it.isNotBlank() }?.let { conn.header("Authorization", "Bearer $it") }

        val body = conn.execute().body()
        val remote = SitesJson.parseRemote(body)
        val result = repo.mergeRemote(remote)
        prefs.lastSync = System.currentTimeMillis()
        return result
    }
}
