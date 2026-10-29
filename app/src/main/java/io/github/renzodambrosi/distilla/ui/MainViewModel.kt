package io.github.renzodambrosi.distilla.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.Article
import io.github.renzodambrosi.distilla.core.ArticleExtractor
import io.github.renzodambrosi.distilla.core.ConfigFormatException
import io.github.renzodambrosi.distilla.core.BrowserTimeoutException
import io.github.renzodambrosi.distilla.core.ConflictOrigin
import io.github.renzodambrosi.distilla.core.HttpErrorException
import io.github.renzodambrosi.distilla.core.History
import io.github.renzodambrosi.distilla.core.HistoryEntry
import io.github.renzodambrosi.distilla.core.ImportResult
import io.github.renzodambrosi.distilla.core.PlayQueue
import io.github.renzodambrosi.distilla.core.QueueItem
import io.github.renzodambrosi.distilla.core.SitesJson
import io.github.renzodambrosi.distilla.core.MergeResult
import io.github.renzodambrosi.distilla.core.RuleSource
import io.github.renzodambrosi.distilla.core.SiteRule
import io.github.renzodambrosi.distilla.core.SyncConflict
import io.github.renzodambrosi.distilla.core.TextRenderer
import io.github.renzodambrosi.distilla.core.UrlMatcher
import io.github.renzodambrosi.distilla.data.ArticleLoader
import io.github.renzodambrosi.distilla.data.ConfigSync
import io.github.renzodambrosi.distilla.data.MissingRemoteUrlException
import io.github.renzodambrosi.distilla.data.Prefs
import io.github.renzodambrosi.distilla.data.RulesRepository
import io.github.renzodambrosi.distilla.playback.PlaybackBus
import io.github.renzodambrosi.distilla.playback.PlaybackService
import io.github.renzodambrosi.distilla.playback.QueueMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import javax.net.ssl.SSLException

enum class Screen { HOME, READER, SETTINGS, QUEUE }

/** The listening queue as seen by the screens (from the MediaController). */
data class NowPlaying(
    val queue: List<QueueItem> = emptyList(),
    /** Story being read, -1 with an empty queue. */
    val current: Int = -1,
    /** Reading (or about to): the play button shows "pause". */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val speed: Float = 1f,
) {
    val currentItem: QueueItem? get() = queue.getOrNull(current)
    fun contains(url: String) = PlayQueue.indexOf(queue.map { it.url }, url) >= 0
}

sealed interface ReaderState {
    data object Idle : ReaderState
    data class Loading(val url: String, val withBrowser: Boolean = false, val resolving: Boolean = false) : ReaderState
    data class Loaded(val article: Article) : ReaderState
    data class Failed(val url: String, val message: String) : ReaderState
}

/** Rule being edited in the dialog. [testUrl] lets the user try the XPath on a real page. */
data class RuleDraft(val rule: SiteRule, val isNew: Boolean, val testUrl: String)

val SPEECH_RATES = listOf(1.0f, 1.5f, 2.0f)

class FileTooLargeException : IllegalStateException("file too large")

enum class ConflictChoice { KEEP_LOCAL, OVERWRITE, KEEP_LOCAL_ALL, OVERWRITE_ALL, DISABLE_ONLINE, LATER }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repo = RulesRepository(app)
    private val configSync = ConfigSync(repo, prefs)
    private val loader = ArticleLoader(app)

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun toast(text: String) = Toast.makeText(getApplication<Application>(), text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ state

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen
    private var screenBeforeSettings = Screen.HOME
    private var screenBeforeQueue = Screen.HOME

    private val _reader = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val reader: StateFlow<ReaderState> = _reader

    private val _rules = MutableStateFlow(repo.load())
    val rules: StateFlow<List<SiteRule>> = _rules

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _syncMessage = MutableStateFlow(prefs.lastSyncMessage)
    val syncMessage: StateFlow<String> = _syncMessage

    private val _remoteUrl = MutableStateFlow(prefs.remoteUrl)
    val remoteUrl: StateFlow<String> = _remoteUrl
    private val _token = MutableStateFlow(prefs.token)
    val token: StateFlow<String> = _token
    private val _autoSync = MutableStateFlow(prefs.autoSync)
    val autoSync: StateFlow<Boolean> = _autoSync
    private val _fontSize = MutableStateFlow(prefs.fontSize)
    val fontSize: StateFlow<Int> = _fontSize

    private val _history = MutableStateFlow(History.fromJson(prefs.historyJson))
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _draft = MutableStateFlow<RuleDraft?>(null)
    val draft: StateFlow<RuleDraft?> = _draft
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    /** Sync conflicts still waiting for a decision. */
    private val _conflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val conflicts: StateFlow<List<SyncConflict>> = _conflicts
    private val _conflictTotal = MutableStateFlow(0)
    val conflictTotal: StateFlow<Int> = _conflictTotal
    private val _conflictDialog = MutableStateFlow(false)
    val conflictDialog: StateFlow<Boolean> = _conflictDialog

    // ------------------------------------------------------------ listening queue (state)

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now
    /** Paragraph, language and loading state of the story being read. */
    val progress: StateFlow<PlaybackBus.Progress> = PlaybackBus.progress
    val playbackNotice: StateFlow<PlaybackBus.Notice?> = PlaybackBus.notice
    fun consumePlaybackNotice() = PlaybackBus.consumeNotice()

    /** Remote control of the playback service; the service starts when it connects. */
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    /** true when reading started from "Share": Back returns to the app the link came from. */
    var openedFromShare = false
        private set

    private var loadJob: Job? = null

    init {
        PlaybackBus.output.value = if (prefs.ttsGuidanceChannel) PlaybackBus.Output.GUIDANCE else PlaybackBus.Output.MEDIA
        withController { c ->
            c.addListener(playerListener)
            refresh(c)
        }
        val day = 24 * 60 * 60 * 1000L
        if (prefs.autoSync && prefs.remoteUrl.isNotBlank() && System.currentTimeMillis() - prefs.lastSync > day) {
            syncNow()
        }
    }

    override fun onCleared() {
        // Only the remote control goes away: the service keeps reading if it is reading
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    // ------------------------------------------------------------ navigation

    fun goHome() {
        _screen.value = Screen.HOME
    }

    fun openQueue() {
        if (_screen.value != Screen.QUEUE) screenBeforeQueue = _screen.value
        _screen.value = Screen.QUEUE
    }

    fun closeQueue() {
        _screen.value =
            if (screenBeforeQueue == Screen.READER && _reader.value != ReaderState.Idle) Screen.READER else Screen.HOME
    }

    fun openSettings() {
        screenBeforeSettings = _screen.value.takeIf { it != Screen.SETTINGS } ?: Screen.HOME
        _screen.value = Screen.SETTINGS
    }

    fun closeSettings() {
        _screen.value =
            if (screenBeforeSettings == Screen.READER && _reader.value != ReaderState.Idle) Screen.READER else Screen.HOME
    }

    // ------------------------------------------------------------ reading

    fun open(rawUrl: String, fromShare: Boolean = false) {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        openedFromShare = fromShare
        updateHistory(History.add(_history.value, url, "", System.currentTimeMillis()))
        _screen.value = Screen.READER
        loadJob?.cancel()
        _reader.value = ReaderState.Loading(url)
        loadJob = viewModelScope.launch {
            var target = url
            // Progress of the shared loader → loading screen and history
            val stages = object : ArticleLoader.Stages {
                override fun resolving(url: String) { _reader.value = ReaderState.Loading(url, resolving = true) }
                override fun resolved(from: String, to: String) {
                    target = to
                    updateHistory(History.replaceUrl(_history.value, from, to))
                    _reader.value = ReaderState.Loading(to)
                }
                override fun usingBrowser(url: String) { _reader.value = ReaderState.Loading(url, withBrowser = true) }
            }
            _reader.value = try {
                // Shortened link → site rule → download (browser engine if blocked) → text
                val article = loader.load(url, stages)
                PlaybackBus.remember(article)   // "Listen" won't download it again
                // Ordinary redirects too: history keeps the address the story was read at
                updateHistory(History.replaceUrl(_history.value, target, article.url))
                updateHistory(History.setTitle(_history.value, article.url, article.title))
                ReaderState.Loaded(article)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderState.Failed(target, describe(e))
            }
        }
    }

    fun reload() {
        when (val s = _reader.value) {
            is ReaderState.Loaded -> open(s.article.requestedUrl, openedFromShare)
            is ReaderState.Failed -> open(s.url, openedFromShare)
            is ReaderState.Loading -> open(s.url, openedFromShare)
            ReaderState.Idle -> Unit
        }
    }

    // ------------------------------------------------------------ history

    fun removeFromHistory(entry: HistoryEntry) = updateHistory(History.remove(_history.value, entry.url))

    fun clearHistory() = updateHistory(emptyList())

    private fun updateHistory(list: List<HistoryEntry>) {
        _history.value = list
        prefs.historyJson = History.toJson(list)
    }

    fun changeFontSize(delta: Int) {
        val v = (_fontSize.value + delta).coerceIn(12, 32)
        _fontSize.value = v
        prefs.fontSize = v
    }

    // ------------------------------------------------------------ listening queue (actions)

    /** Runs [block] with the connected controller (at once, or as soon as it connects). */
    private fun withController(block: (MediaController) -> Unit) {
        controllerFuture.addListener(
            { runCatching { controllerFuture.get() }.getOrNull()?.let(block) },
            ContextCompat.getMainExecutor(getApplication<Application>()),
        )
    }

    private fun refresh(p: Player) {
        val queue = (0 until p.mediaItemCount).map { QueueMedia.toQueueItem(p.getMediaItemAt(it)) }
        _now.value = NowPlaying(
            queue = queue,
            current = if (queue.isEmpty()) -1 else p.currentMediaItemIndex,
            playing = p.playWhenReady,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            ended = p.playbackState == Player.STATE_ENDED,
            speed = p.playbackParameters.speed,
        )
    }

    private fun MediaController.urls() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    private fun MediaController.ensurePrepared() {
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    /** History "add to queue": appended at the end, without starting. */
    fun enqueue(entry: HistoryEntry) = withController { c ->
        if (PlayQueue.indexOf(c.urls(), entry.url) >= 0) {
            toast(str(R.string.in_queue))
            return@withController
        }
        c.addMediaItem(QueueMedia.item(entry.url, entry.title))
        c.ensurePrepared()
        toast(str(R.string.queued))
    }

    /**
     * History "listen to all": the stories not yet queued are added (most recent first)
     * and reading starts from the first of them, unless something is already being read.
     */
    fun listenAll() = withController { c ->
        val new = PlayQueue.distinctNew(c.urls(), _history.value) { it.url }
        val firstNew = c.mediaItemCount
        if (new.isNotEmpty()) c.addMediaItems(new.map { QueueMedia.item(it.url, it.title) })
        if (c.mediaItemCount == 0) return@withController
        c.ensurePrepared()
        if (!c.playWhenReady) {
            if (new.isNotEmpty()) c.seekTo(firstNew, 0)
            c.play()
        }
        toast(getApplication<Application>().resources.getQuantityString(R.plurals.queued_n, new.size, new.size))
    }

    /** Reader "Listen": this story now, right after the current one in the queue. */
    fun playArticle(article: Article) {
        PlaybackBus.remember(article)
        withController { c ->
            var index = PlayQueue.indexOf(c.urls(), article.url)
            if (index < 0) {
                index = if (c.mediaItemCount == 0) 0 else (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
                c.addMediaItem(index, QueueMedia.item(article.url, article.title))
            }
            c.ensurePrepared()
            c.seekTo(index, 0)
            c.play()
        }
    }

    fun togglePlay() = withController { c ->
        if (c.playWhenReady) c.pause() else { c.ensurePrepared(); c.play() }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    /** Back to the start of the story, or to the previous one if just started. */
    fun previous() = withController { it.seekToPrevious() }

    fun playQueueItem(index: Int) = withController { c ->
        c.ensurePrepared()
        c.seekTo(index, 0)
        c.play()
    }

    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }

    fun moveInQueue(from: Int, to: Int) = withController { c ->
        if (to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = withController { it.clearMediaItems() }

    /** Cycles 1× → 1.5× → 2× → 1×. */
    fun cycleSpeed() = withController { c ->
        val speed = c.playbackParameters.speed
        c.setPlaybackSpeed(SPEECH_RATES.firstOrNull { it > speed + 0.01f } ?: SPEECH_RATES.first())
    }

    // ------------------------------------------------------------ settings and sync

    fun setRemoteUrl(v: String) { _remoteUrl.value = v; prefs.remoteUrl = v }
    fun setToken(v: String) { _token.value = v; prefs.token = v }
    fun setAutoSync(v: Boolean) { _autoSync.value = v; prefs.autoSync = v }

    private val _userAgent = MutableStateFlow(prefs.userAgent)
    val userAgent: StateFlow<String> = _userAgent
    fun setUserAgent(v: String) { _userAgent.value = v; prefs.userAgent = v }

    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val msg = try {
                val result = withContext(Dispatchers.IO) { configSync.sync() }
                _rules.value = repo.load()
                val stillFromFile = _conflicts.value.filter { it.origin == ConflictOrigin.FILE }
                _conflicts.value = stillFromFile
                _conflictTotal.value = stillFromFile.size
                if (result.conflicts.isNotEmpty()) addConflicts(result.conflicts)
                describe(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                str(R.string.err_prefix, describe(e))
            }
            val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
            val full = "$stamp – $msg"
            prefs.lastSyncMessage = full
            _syncMessage.value = full
            _syncing.value = false
        }
    }

    private fun describe(r: MergeResult): String = buildString {
        append(str(R.string.sync_result, r.total, r.added, r.updated, r.removed))
        if (r.keptLocal > 0) append(' ').append(str(R.string.sync_kept, r.keptLocal))
        if (r.conflicts.isNotEmpty()) append(' ').append(str(R.string.sync_conflicts, r.conflicts.size))
    }

    // ------------------------------------------------------------ import from file

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    /** Reads a sites.json picked with the system file picker and adds its rules as local rules. */
    fun importFromFile(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val msg = try {
                val result = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 5 * 1024 * 1024) throw FileTooLargeException()
                        bytes.toString(Charsets.UTF_8)
                    } ?: throw java.io.FileNotFoundException(uri.toString())
                    repo.importRules(SitesJson.parseRemote(text))
                }
                _rules.value = repo.load()
                if (result.conflicts.isNotEmpty()) addConflicts(result.conflicts)
                describe(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                str(R.string.import_error, describe(e))
            }
            Toast.makeText(getApplication<Application>(), msg, Toast.LENGTH_LONG).show()
            _importing.value = false
        }
    }

    /** Writes all the rules as sites.json to a file chosen with the system "save as" picker. */
    fun exportToFile(uri: Uri) {
        viewModelScope.launch {
            val msg = try {
                val count = withContext(Dispatchers.IO) {
                    val json = repo.exportJson()
                    // "wt" = truncate: overwriting an existing, longer file must not leave old bytes behind
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw java.io.FileNotFoundException(uri.toString())
                    _rules.value.size
                }
                str(R.string.export_done, count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                str(R.string.export_error, describe(e))
            }
            Toast.makeText(getApplication<Application>(), msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Suggested file name for the backup, e.g. distilla-sites-2026-10-27.json */
    fun exportFileName(): String =
        "distilla-sites-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date()) + ".json"

    private fun describe(r: ImportResult): String = buildString {
        append(str(R.string.import_result, r.total, r.added))
        if (r.identical > 0) append(' ').append(str(R.string.sync_kept, r.identical))
        if (r.conflicts.isNotEmpty()) append(' ').append(str(R.string.sync_conflicts, r.conflicts.size))
    }

    /** Queues new conflicts after the ones still pending (a newer one for the same URL replaces the older). */
    private fun addConflicts(new: List<SyncConflict>) {
        val keys = new.map { it.key }.toSet()
        val merged = _conflicts.value.filterNot { it.key in keys } + new
        _conflicts.value = merged
        _conflictTotal.value = merged.size
        _conflictDialog.value = true
    }

    fun showConflicts() { if (_conflicts.value.isNotEmpty()) _conflictDialog.value = true }

    /** Answer for the first pending conflict (or for all of them, with the "all" choices). */
    fun resolveConflict(choice: ConflictChoice) {
        val pending = _conflicts.value
        if (pending.isEmpty()) { _conflictDialog.value = false; return }
        val first = listOf(pending.first())
        val remaining: List<SyncConflict> = when (choice) {
            ConflictChoice.KEEP_LOCAL -> { updateRules { repo.keepLocal(first) }; pending.drop(1) }
            ConflictChoice.OVERWRITE -> { updateRules { repo.overwrite(first) }; pending.drop(1) }
            ConflictChoice.KEEP_LOCAL_ALL -> { updateRules { repo.keepLocal(pending) }; emptyList() }
            ConflictChoice.OVERWRITE_ALL -> { updateRules { repo.overwrite(pending) }; emptyList() }
            ConflictChoice.DISABLE_ONLINE -> {
                // Local rules stay as they are; nothing is remembered, so turning
                // updates back on will ask again.
                setAutoSync(false)
                Toast.makeText(getApplication<Application>(), R.string.online_disabled_msg, Toast.LENGTH_LONG).show()
                emptyList()
            }
            ConflictChoice.LATER -> { _conflictDialog.value = false; pending }
        }
        _conflicts.value = remaining
        if (remaining.isEmpty()) _conflictDialog.value = false
    }

    private fun updateRules(change: () -> List<SiteRule>) {
        _rules.value = change()
    }

    fun exportJson(): String = repo.exportJson()

    // ------------------------------------------------------------ rules

    /** Opens the editor: edits the rule used by the article, or proposes a new one. */
    fun ruleFromArticle(article: Article) {
        val existing = article.rule?.let { r -> _rules.value.firstOrNull { it.id == r.id } }
        if (existing != null) {
            editRule(existing, article.url)
            return
        }
        val host = article.host.removePrefix("www.")
        newRule(
            SiteRule(name = host, pattern = UrlMatcher.suggestPattern(article.url), xpath = article.suggestedXpath.orEmpty()),
            article.url,
        )
    }

    fun newRule(rule: SiteRule = SiteRule(pattern = "", xpath = ""), testUrl: String = "") {
        _testResult.value = null
        _draft.value = RuleDraft(rule, isNew = true, testUrl = testUrl)
    }

    fun editRule(rule: SiteRule, testUrl: String = "") {
        _testResult.value = null
        _draft.value = RuleDraft(rule, isNew = false, testUrl = testUrl)
    }

    fun dismissDraft() { _draft.value = null }

    fun saveRule(rule: SiteRule) {
        val clean = rule.copy(
            name = rule.name.trim(),
            pattern = rule.pattern.trim(),
            xpath = rule.xpath.trim(),
            include = rule.include.trim().trim(',').trim(),
            exclude = rule.exclude.trim().trim(',').trim(),
            userAgent = rule.userAgent.trim(),
            source = RuleSource.USER,
        )
        viewModelScope.launch {
            _rules.value = withContext(Dispatchers.IO) { repo.upsert(clean) }
            _draft.value = null
            // While reading, re-read the story with the new rule
            if (_screen.value == Screen.READER) reload()
        }
    }

    fun deleteRule(rule: SiteRule) {
        viewModelScope.launch {
            _rules.value = withContext(Dispatchers.IO) { repo.delete(rule.id) }
        }
    }

    private fun preview(text: String) = if (text.length > 500) text.take(500) + "…" else text

    private val _ttsGuidance = MutableStateFlow(prefs.ttsGuidanceChannel)
    val ttsGuidance: StateFlow<Boolean> = _ttsGuidance
    fun setTtsGuidance(v: Boolean) {
        _ttsGuidance.value = v
        prefs.ttsGuidanceChannel = v
        PlaybackBus.output.value = if (v) PlaybackBus.Output.GUIDANCE else PlaybackBus.Output.MEDIA
    }

    private val _browserFallback = MutableStateFlow(prefs.browserFallback)
    val browserFallback: StateFlow<Boolean> = _browserFallback
    fun setBrowserFallback(v: Boolean) { _browserFallback.value = v; prefs.browserFallback = v }

    /** Tries the whole rule (User-Agent, browser engine, XPath, CSS filters) on a real page and shows a preview. */
    fun testRule(rawUrl: String, rule: SiteRule) {
        val typed = rawUrl.trim().let { if (it.startsWith("http", true)) it else "https://$it" }
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            _testResult.value = try {
                // A shortened test URL is resolved too, so the rule is tried on the real page
                val url = loader.resolve(typed)
                val (doc, withBrowser) = loader.loadDocument(url, rule)
                val via = (if (url != typed) str(R.string.test_resolved, url) + "\n" else "") +
                    (if (withBrowser) str(R.string.test_via_browser) + "\n" else "")
                via + withContext(Dispatchers.Default) {
                    if (rule.hasSelection) {
                        val found = ArticleExtractor.selectWithRule(doc, rule)
                        val text = TextRenderer.render(found)
                        if (found.isEmpty() || text.isBlank()) str(R.string.test_none)
                        else str(R.string.test_found, found.size, text.length) + "\n\n" + preview(text)
                    } else {
                        // No selection (only User-Agent / browser engine): show what automatic detection finds
                        val text = ArticleExtractor.extract(doc, url, null).text
                        if (text.isBlank()) str(R.string.test_none)
                        else str(R.string.test_auto, text.length) + "\n\n" + preview(text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                str(R.string.err_prefix, describe(e))
            }
            _testing.value = false
        }
    }

    // ------------------------------------------------------------ errors

    private fun describe(e: Throwable): String = when (e) {
        is HttpErrorException -> buildString {
            append(
                if (e.server.isNullOrBlank()) str(R.string.err_http, e.status)
                else str(R.string.err_http_server, e.status, e.server)
            )
            if (e.looksBlocked) append(' ').append(str(R.string.err_blocked_hint))
        }
        is BrowserTimeoutException -> str(R.string.err_browser_timeout)
        is HttpStatusException -> str(R.string.err_http, e.statusCode)
        is UnsupportedMimeTypeException -> str(R.string.err_mime, e.mimeType ?: "?")
        is UnknownHostException -> str(R.string.err_host)
        is SocketTimeoutException -> str(R.string.err_timeout)
        is SSLException -> str(R.string.err_ssl)
        is MalformedURLException -> str(R.string.err_url)
        is MissingRemoteUrlException -> str(R.string.err_no_remote_url)
        is ConfigFormatException -> str(R.string.err_config_format)
        is java.io.FileNotFoundException -> str(R.string.err_file_unreadable)
        is FileTooLargeException -> str(R.string.err_file_too_large)
        is SerializationException -> str(R.string.err_json)
        is IllegalArgumentException -> str(R.string.err_url)
        else -> e.message ?: e.javaClass.simpleName
    }
}
