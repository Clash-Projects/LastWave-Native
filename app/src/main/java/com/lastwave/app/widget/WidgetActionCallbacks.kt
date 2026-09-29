package com.lastwave.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class ToggleActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Immediate 0ms local toggle in Glance state so the icon flips in the current frame!
        runCatching {
            androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { prefs ->
                val isPlaying = prefs[WidgetPrefKeys.IS_PLAYING] ?: false
                prefs[WidgetPrefKeys.IS_PLAYING] = !isPlaying
            }
            TwoToneSplitWidget().update(context, glanceId)
        }
        WidgetActions.performToggle(context, glanceId)
    }
}

class NextActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.performSkip(context, next = true, glanceId = glanceId)
    }
}

class PrevActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.performSkip(context, next = false, glanceId = glanceId)
    }
}

class FavoriteActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = runCatching {
            androidx.glance.appwidget.state.getAppWidgetState(
                context,
                androidx.glance.state.PreferencesGlanceStateDefinition,
                glanceId,
            )
        }.getOrNull()
        val snapshot = if (prefs != null) {
            WidgetSnapshot.fromPreferences(prefs, context)
        } else {
            WidgetSnapshot.read(context)
        }

        // Guard: Favorite button should NOT work when nothing is playing, empty state, or app not active
        if (!WidgetActions.isFavoriteActionAvailable(context, snapshot)) {
            return
        }

        // Immediate 0ms local toggle in Glance state so the heart flips in the current frame!
        runCatching {
            androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { p ->
                val isFav = p[WidgetPrefKeys.IS_FAVORITE] ?: false
                p[WidgetPrefKeys.IS_FAVORITE] = !isFav
            }
            TwoToneSplitWidget().update(context, glanceId)
        }
        WidgetActions.performFavorite(context, glanceId)
    }
}

class ShuffleActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.performShuffle(context, glanceId)
    }
}

class RepeatActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.performRepeat(context, glanceId)
    }
}
