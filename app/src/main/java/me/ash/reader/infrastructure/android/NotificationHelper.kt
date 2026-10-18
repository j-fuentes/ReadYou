package me.ash.reader.infrastructure.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.get
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.common.NotificationGroupName
import timber.log.Timber

class NotificationHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val coroutineScope: CoroutineScope,
) {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context).apply {
            createNotificationChannel(
                NotificationChannel(
                        NotificationGroupName.ARTICLE_UPDATE,
                        NotificationGroupName.ARTICLE_UPDATE,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply { setShowBadge(true) }
            )
        }

    /** Articles collected during the current sync, waiting to be summarized. */
    private val pendingMutex = Mutex()
    private val pending = mutableListOf<Pair<Feed, Article>>()

    private val isSummaryEnabled: Boolean
        get() = context.dataStore.get<Boolean>(DataStoreKey.notificationSummary) ?: false

    private val isBadgeEnabled: Boolean
        get() = context.dataStore.get<Boolean>(DataStoreKey.notificationBadge) ?: true

    fun notify(feed: Feed, articles: List<Article>) {
        if (!notificationManager.areNotificationsEnabled()) return
        if (articles.isEmpty()) return
        if (!feed.isNotification) return

        if (isSummaryEnabled) {
            coroutineScope.launch {
                pendingMutex.withLock { articles.forEach { pending.add(feed to it) } }
            }
            return
        }

        notifyPerFeed(feed, articles)
    }

    fun notify(feedWithArticle: FeedWithArticle) {
        notify(feedWithArticle.feed, feedWithArticle.articles)
    }

    /**
     * Posts a single notification summarizing every new article collected since the last flush,
     * and updates the launcher icon badge count. Called at the end of a sync.
     */
    fun flushSummary() {
        if (!isSummaryEnabled) return
        coroutineScope.launch {
            val items = pendingMutex.withLock { pending.toList().also { pending.clear() } }
            if (items.isEmpty()) return@launch
            if (!notificationManager.areNotificationsEnabled()) return@launch

            Timber.d("notify summary for ${items.size} articles")

            val count = items.size
            val feedCount = items.map { it.first.id }.distinct().size
            val style = NotificationCompat.InboxStyle()
            items.take(MAX_SUMMARY_LINES).forEach { (feed, article) ->
                style.addLine("${feed.name} · ${article.title}")
            }
            if (count > MAX_SUMMARY_LINES) {
                style.setSummaryText(
                    context.getString(R.string.notification_summary_more, count - MAX_SUMMARY_LINES)
                )
            } else {
                style.setSummaryText(
                    context.resources.getQuantityString(
                        R.plurals.notification_summary_feeds,
                        feedCount,
                        feedCount,
                    )
                )
            }

            val builder =
                NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(
                        context.resources.getQuantityString(
                            R.plurals.notification_summary_title,
                            count,
                            count,
                        )
                    )
                    .setContentText(items.take(3).joinToString(" · ") { it.second.title })
                    .setStyle(style)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            SUMMARY_NOTIFICATION_ID,
                            Intent(context, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )

            // Launcher icon badge: launchers that support numeric badges read this value,
            // the rest show a plain dot while the notification is active.
            if (isBadgeEnabled) {
                builder.setNumber(count)
            } else {
                builder.setNumber(0)
            }

            notificationManager.notify(SUMMARY_NOTIFICATION_ID, builder.build())
        }
    }

    private fun notifyPerFeed(feed: Feed, articles: List<Article>) {
        coroutineScope.launch {
            Timber.d("notify ${feed.name} for ${articles.size} articles")

            val favIcon =
                withContext(ioDispatcher) {
                    feed.icon?.let { icon ->
                        context.imageLoader
                            .execute(ImageRequest.Builder(context).data(icon).build())
                            .drawable
                            ?.toBitmapOrNull()
                    }
                }

            notificationManager.notify(
                feed.id.hashCode(),
                NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
                    .setContentTitle(feed.name)
                    .setContentText(
                        context.resources.getQuantityText(R.plurals.unread_desc, articles.size)
                    )
                    .setSmallIcon(R.drawable.ic_notification)
                    .setStyle(NotificationCompat.InboxStyle().setSummaryText(feed.name))
                    .setGroup(feed.id)
                    .setGroupSummary(true)
                    .build(),
            )

            articles.asReversed().forEachIndexed { _, article ->
                val builder =
                    NotificationCompat.Builder(context, NotificationGroupName.ARTICLE_UPDATE)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setSubText(feed.name)
                        .setAutoCancel(true)
                        .setContentTitle(article.title)
                        .setContentText(article.shortDescription)
                        .setLargeIcon(favIcon)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                Random().nextInt() + article.id.hashCode(),
                                Intent(context, MainActivity::class.java).apply {
                                    flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra(ExtraName.ARTICLE_ID, article.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        )
                        .setGroup(feed.id)
                notificationManager.notify(
                    Random().nextInt() + article.id.hashCode(),
                    builder.build(),
                )
            }
        }
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0x52_59
        private const val MAX_SUMMARY_LINES = 8
    }
}
