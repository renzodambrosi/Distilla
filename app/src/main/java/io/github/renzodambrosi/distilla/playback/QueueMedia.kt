package io.github.renzodambrosi.distilla.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.renzodambrosi.distilla.core.QueueItem

/** How a story of the queue is described to Media3 (notification, lock screen, Android Auto). */
object QueueMedia {

    /** The story's address is its id: one story appears at most once in the queue. */
    fun item(url: String, title: String): MediaItem {
        val host = QueueItem(url).host
        return MediaItem.Builder()
            .setMediaId(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { host })
                    .setArtist(host)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    fun item(q: QueueItem): MediaItem = item(q.url, q.title)

    /** Title as shown, or "" when it is still the placeholder (the site name). */
    fun titleOf(item: MediaItem): String {
        val title = item.mediaMetadata.title?.toString().orEmpty()
        return if (title == QueueItem(item.mediaId).host) "" else title
    }

    fun toQueueItem(item: MediaItem) = QueueItem(item.mediaId, titleOf(item))
}
