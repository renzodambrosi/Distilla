package io.github.renzodambrosi.distilla.data

import android.content.Context
import io.github.renzodambrosi.distilla.core.ImportResult
import io.github.renzodambrosi.distilla.core.MergeResult
import io.github.renzodambrosi.distilla.core.RuleMerger
import io.github.renzodambrosi.distilla.core.RuleSource
import io.github.renzodambrosi.distilla.core.SiteRule
import io.github.renzodambrosi.distilla.core.SitesJson
import io.github.renzodambrosi.distilla.core.SyncConflict
import java.io.File

/**
 * Rules stored in files/rules.json.
 *
 *  - USER   → created or edited in the app. Syncing never changes them without asking:
 *             a different XPath on Git for the same URL becomes a conflict for the user.
 *  - REMOTE → downloaded from Git, updated or removed on every sync.
 *
 * The merge logic itself is in [RuleMerger] (pure Kotlin, unit-tested).
 */
class RulesRepository(context: Context) {
    private val file = File(context.filesDir, "rules.json")
    private val lock = Any()

    fun load(): List<SiteRule> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { SitesJson.fromStorage(file.readText()) }.getOrElse { emptyList() }
    }

    private fun save(rules: List<SiteRule>): List<SiteRule> = synchronized(lock) {
        val sorted = rules.sortedBy { it.displayName.lowercase() }
        val tmp = File(file.parentFile, "rules.json.tmp")
        tmp.writeText(SitesJson.toStorage(sorted))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        sorted
    }

    /** Saves a user rule, replacing any rule with the same pattern. */
    fun upsert(rule: SiteRule): List<SiteRule> = synchronized(lock) {
        val r = rule.copy(source = RuleSource.USER)
        save(load().filterNot { it.id == r.id || it.key == r.key } + r)
    }

    fun delete(id: String): List<SiteRule> = synchronized(lock) {
        save(load().filterNot { it.id == id })
    }

    /** Applies everything that needs no decision; conflicts are returned in the result. */
    fun mergeRemote(remote: List<SiteRule>): MergeResult = synchronized(lock) {
        val outcome = RuleMerger.merge(load(), remote)
        save(outcome.rules)
        outcome.result
    }

    /** Adds the rules of an imported sites.json as local rules; conflicts are returned. */
    fun importRules(incoming: List<SiteRule>): ImportResult = synchronized(lock) {
        val (rules, result) = RuleMerger.importRules(load(), incoming)
        save(rules)
        result
    }

    fun overwrite(conflicts: List<SyncConflict>): List<SiteRule> = synchronized(lock) {
        save(RuleMerger.overwrite(load(), conflicts))
    }

    fun keepLocal(conflicts: List<SyncConflict>): List<SiteRule> = synchronized(lock) {
        save(RuleMerger.keepLocal(load(), conflicts))
    }

    fun exportJson(): String = SitesJson.toSitesFile(load())
}
