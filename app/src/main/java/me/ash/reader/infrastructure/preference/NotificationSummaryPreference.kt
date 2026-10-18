package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.notificationSummary
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalNotificationSummary = compositionLocalOf<NotificationSummaryPreference> { NotificationSummaryPreference.default }

sealed class NotificationSummaryPreference(val value: Boolean) : Preference() {
    data object ON : NotificationSummaryPreference(true)

    data object OFF : NotificationSummaryPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch { context.dataStore.put(notificationSummary, value) }
    }

    fun toggle(context: Context, scope: CoroutineScope) =
        scope.launch { context.dataStore.put(notificationSummary, !value) }

    companion object {

        val default = OFF
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[notificationSummary]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
