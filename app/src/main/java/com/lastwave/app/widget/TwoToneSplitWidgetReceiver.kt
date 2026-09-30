package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receiver for the Material You Two-Tone Split Card Widget.
 * Handles AppWidget lifecycle callbacks and broadcasts for media playback controls.
 */
class TwoToneSplitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TwoToneSplitWidget()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ioScope.launch {
            runCatching {
                WidgetUpdater.sync(context)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ioScope.launch {
            runCatching {
                WidgetUpdater.sync(context)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV,
            WidgetActions.ACTION_FAVORITE,
            WidgetActions.ACTION_SHUFFLE,
            WidgetActions.ACTION_REPEAT -> {
                val action = intent.action ?: return
                val pending = goAsync()
                ioScope.launch {
                    try {
                        when (action) {
                            WidgetActions.ACTION_TOGGLE -> WidgetActions.performToggle(context)
                            WidgetActions.ACTION_NEXT -> WidgetActions.performSkip(context, next = true)
                            WidgetActions.ACTION_PREV -> WidgetActions.performSkip(context, next = false)
                            WidgetActions.ACTION_FAVORITE -> WidgetActions.performFavorite(context)
                            WidgetActions.ACTION_SHUFFLE -> WidgetActions.performShuffle(context)
                            WidgetActions.ACTION_REPEAT -> WidgetActions.performRepeat(context)
                        }
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
                return
            }
            else -> super.onReceive(context, intent)
        }
    }
}
