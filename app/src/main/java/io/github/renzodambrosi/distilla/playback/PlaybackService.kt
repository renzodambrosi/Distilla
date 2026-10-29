package io.github.renzodambrosi.distilla.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.renzodambrosi.distilla.MainActivity
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.History
import io.github.renzodambrosi.distilla.core.PlayQueue
import io.github.renzodambrosi.distilla.data.Prefs

/**
 * Background service that owns the [TtsPlayer]. Media3 turns it into a foreground
 * service with a media notification while reading, and exposes it to the lock screen,
 * Bluetooth / steering-wheel buttons and Android Auto (as a media library with two
 * folders: the listening queue and the recently read stories).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val player = TtsPlayer(this)
        // Tapping the notification (or "open app" in the car) opens the queue screen
        val openQueue = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(player))
            .setSessionActivity(openQueue)
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_notification) }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** App swiped away from recents: keep reading if it is reading, otherwise close. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ media library (Android Auto)

    private inner class LibraryCallback(private val player: TtsPlayer) : MediaLibrarySession.Callback {

        private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

        private fun recent(): List<MediaItem> =
            History.fromJson(Prefs(this@PlaybackService).historyJson).take(RECENT_LIMIT).map { QueueMedia.item(it.url, it.title) }

        /** Fills in title and site for an item that arrives with only its id (from Android Auto). */
        private fun complete(item: MediaItem): MediaItem {
            if (item.mediaMetadata.title != null) return item
            val known = (player.queueItems() + recent()).firstOrNull { PlayQueue.sameUrl(it.mediaId, item.mediaId) }
            return known ?: QueueMedia.item(item.mediaId, "")
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when (mediaId) {
                ROOT -> folder(ROOT, getString(R.string.app_name))
                QUEUE -> folder(QUEUE, getString(R.string.queue_title))
                RECENT -> folder(RECENT, getString(R.string.folder_recent))
                else -> complete(MediaItem.Builder().setMediaId(mediaId).build())
            }
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = when (parentId) {
                ROOT -> listOf(folder(QUEUE, getString(R.string.queue_title)), folder(RECENT, getString(R.string.folder_recent)))
                QUEUE -> player.queueItems()
                RECENT -> recent()
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        /** Items from the app already have their metadata; the ones from the car get it here. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::complete).toMutableList())

        /**
         * A tap on a story in Android Auto: keep the queue and jump to that story,
         * adding it at the end if it isn't queued yet.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size != 1) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems.map(::complete), startIndex, startPositionMs)
                )
            }
            val chosen = complete(mediaItems.first())
            val queue = player.queueItems().toMutableList()
            var index = queue.indexOfFirst { PlayQueue.sameUrl(it.mediaId, chosen.mediaId) }
            if (index < 0) {
                queue += chosen
                index = queue.lastIndex
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, index, C.TIME_UNSET))
        }

        /** "Resume" from the system media controls after a restart: the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = PlayQueue.fromJson(Prefs(this@PlaybackService).queueJson)
            if (saved.items.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("empty queue"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.items.map(QueueMedia::item), saved.current, saved.chunk * 1000L)
            )
        }
    }

    private companion object {
        const val ROOT = "distilla:root"
        const val QUEUE = "distilla:queue"
        const val RECENT = "distilla:recent"
        const val RECENT_LIMIT = 30
    }
}
