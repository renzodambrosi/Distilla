package io.github.renzodambrosi.distilla.core

/** Where the incoming rule of a conflict comes from. */
enum class ConflictOrigin { GIT, FILE }

/**
 * An incoming rule (from Git or from an imported file) whose URL already exists
 * locally with a different configuration.
 */
data class SyncConflict(val local: SiteRule, val remote: SiteRule, val origin: ConflictOrigin = ConflictOrigin.GIT) {
    val key: String get() = local.key
}

data class ImportResult(
    val total: Int,
    val added: Int,
    /** Already present with the same configuration. */
    val identical: Int,
    val conflicts: List<SyncConflict>,
)

data class MergeResult(
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** Git rules skipped because an identical (or already ignored) local rule exists. */
    val keptLocal: Int,
    val conflicts: List<SyncConflict>,
)

data class MergeOutcome(val rules: List<SiteRule>, val result: MergeResult)

/**
 * Merges the Git configuration into the local rules.
 *
 *  - REMOTE rules mirror the repository: added, updated or removed.
 *  - USER rules are never touched here. When Git has the same URL with a different configuration
 *    (XPath or CSS selectors) the pair becomes a [SyncConflict], and the user decides with [overwrite] or [keepLocal].
 *  - A conflict the user already answered with "keep local" is not asked again
 *    until the Git configuration changes.
 */
object RuleMerger {

    fun merge(current: List<SiteRule>, remote: List<SiteRule>): MergeOutcome {
        val user = current.filter { it.source == RuleSource.USER }
        val userByKey = user.associateBy { it.key }
        val oldRemote = current.filter { it.source == RuleSource.REMOTE }.associateBy { it.key }
        val incoming = remote.distinctBy { it.key }

        var added = 0
        var updated = 0
        var keptLocal = 0
        val conflicts = mutableListOf<SyncConflict>()
        val merged = mutableListOf<SiteRule>()

        for (r in incoming) {
            val local = userByKey[r.key]
            if (local != null) {
                if (local.signature == r.signature || local.ignoredRemote == r.signature) keptLocal++
                else conflicts += SyncConflict(local, r)
                continue
            }
            val old = oldRemote[r.key]
            merged += when {
                old == null -> { added++; r.copy(source = RuleSource.REMOTE) }
                old.signature != r.signature || old.name != r.name || old.pattern != r.pattern -> {
                    updated++
                    r.copy(id = old.id, source = RuleSource.REMOTE)
                }
                else -> old
            }
        }
        val incomingKeys = incoming.map { it.key }.toSet()
        val removed = oldRemote.keys.count { it !in incomingKeys || it in userByKey }

        return MergeOutcome(
            rules = user + merged,
            result = MergeResult(incoming.size, added, updated, removed, keptLocal, conflicts),
        )
    }

    /**
     * Imports rules from a file the user picked. Unlike Git it only adds:
     * nothing is removed, and the imported rules become USER rules.
     * A URL already present (local or from Git) with a different configuration is a conflict.
     */
    fun importRules(current: List<SiteRule>, incoming: List<SiteRule>): Pair<List<SiteRule>, ImportResult> {
        val byKey = current.associateBy { it.key }
        val unique = incoming.distinctBy { it.key }
        val added = mutableListOf<SiteRule>()
        val conflicts = mutableListOf<SyncConflict>()
        var identical = 0
        for (r in unique) {
            val existing = byKey[r.key]
            when {
                existing == null -> added += r.copy(source = RuleSource.USER, ignoredRemote = null)
                existing.signature == r.signature -> identical++
                else -> conflicts += SyncConflict(existing, r.copy(source = RuleSource.USER), ConflictOrigin.FILE)
            }
        }
        return (current + added) to ImportResult(unique.size, added.size, identical, conflicts)
    }

    /**
     * "Overwrite": the incoming rule replaces the existing one.
     * From Git it then follows the repository; from a file it becomes a local rule.
     */
    fun overwrite(current: List<SiteRule>, conflicts: List<SyncConflict>): List<SiteRule> {
        val byKey = conflicts.associateBy { it.key }
        return current.map { rule ->
            val c = byKey[rule.key]
            when {
                c == null -> rule
                c.origin == ConflictOrigin.GIT && rule.source == RuleSource.USER ->
                    c.remote.copy(id = rule.id, source = RuleSource.REMOTE, ignoredRemote = null)
                c.origin == ConflictOrigin.FILE ->
                    c.remote.copy(id = rule.id, source = RuleSource.USER, ignoredRemote = rule.ignoredRemote)
                else -> rule
            }
        }
    }

    /**
     * "Keep local": the existing rule stays. For Git the incoming configuration is
     * remembered so the same conflict isn't asked again; for a file nothing changes.
     */
    fun keepLocal(current: List<SiteRule>, conflicts: List<SyncConflict>): List<SiteRule> {
        val byKey = conflicts.filter { it.origin == ConflictOrigin.GIT }.associateBy { it.key }
        return current.map { rule ->
            val c = byKey[rule.key]
            if (c != null && rule.source == RuleSource.USER) rule.copy(ignoredRemote = c.remote.signature) else rule
        }
    }
}
