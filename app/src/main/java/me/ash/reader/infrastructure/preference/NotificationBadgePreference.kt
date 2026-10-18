package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.notificationBadge
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalNotificationBadge = compositionLocalOf<NotificationBadgePreference> { NotificationBadgePreference.default }

sealed class NotificationBadgePreference(val value: Boolean) : Preference() {
    data object ON : NotificationBadgePreference(true)

    data object OFF : NotificationBadgePreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch { context.dataStore.put(notificationBadge, value) }
    }

    fun toggle(context: Context, scope: CoroutineScope) =
        scope.launch { context.dataStore.put(notificationBadge, !value) }

    companion object {

        val default = ON
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[notificationBadge]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
