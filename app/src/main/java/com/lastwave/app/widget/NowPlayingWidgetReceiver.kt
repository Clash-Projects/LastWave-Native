package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one now-playing widget: classic AppWidgetProvider + a single adaptive
 * RemoteViews layout.
 */
class NowPlayingWidgetReceiver : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (appWidgetId in ids) {
            runCatching {
                manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId))
            }
        }
        ioScope.launch {
            runCatching {
                val snapshot = WidgetSnapshot.read(context)
                if (snapshot.hasSession && snapshot.isPlaying && WidgetActions.isAppInBackground(context)) {
                    WidgetUpdater.startWaveAnimation(context.applicationContext)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        runCatching {
            manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV,
            -> {
                val action = intent.action ?: return
                val pending = goAsync()
                ioScope.launch {
                    try {
                        when (action) {
                            WidgetActions.ACTION_TOGGLE -> WidgetActions.performToggle(context)
                            WidgetActions.ACTION_NEXT -> WidgetActions.performSkip(context, next = true)
                            WidgetActions.ACTION_PREV -> WidgetActions.performSkip(context, next = false)
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

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ioScope.launch { runCatching { WidgetUpdater.sync(context) } }
    }
}
