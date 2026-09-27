package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import com.lastwave.app.service.MediaScrobbleListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one now-playing widget: classic AppWidgetProvider + a single adaptive
 * RemoteViews layout. The binder hides elements per measured width
 * (compact / standard / expanded) — there is exactly one provider, one
 * layout, one snapshot, so a duplicate can never render.
 *
 * onUpdate always pushes fully-built content (or the empty state) with
 * every call guarded — no code path leaves the widget without content.
 */
class NowPlayingWidgetReceiver : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (appWidgetId in ids) {
            runCatching {
                manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId))
            }
        }
        // Restart the EQ/progress ticker after process death when the
        // persisted snapshot says something is still playing.
        ioScope.launch {
            runCatching {
                val snapshot = WidgetSnapshot.read(context)
                if (snapshot.hasSession && snapshot.isPlaying) {
                    WidgetUpdater.startWaveAnimation(context.applicationContext)
                }
            }
        }
    }

    /**
     * The user resized the widget: re-resolve the breakpoint for the new
     * allocation right away instead of waiting for the next track event.
     */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, MediaScrobbleListenerService::class.java),
                )
            }
        }
        // Freshly placed widget must show persisted state immediately,
        // even if the service hasn't published since boot.
        ioScope.launch { runCatching { WidgetUpdater.sync(context) } }
    }
}
