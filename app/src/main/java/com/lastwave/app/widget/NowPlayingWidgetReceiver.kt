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
 * From-scratch widget, classic AppWidgetProvider + static RemoteViews.
 *
 * Why this fixes "widget not loading": the old Glance widget built its UI
 * inside provideGlance() behind Hilt + theme-repo lookups, and its
 * initialLayout was glance_default_loading_layout. Any throw before
 * provideContent left the spinner on screen forever. Here onUpdate always
 * pushes a fully-built RemoteViews (or the empty state) with every call
 * guarded — there is no code path that leaves the widget without content.
 */
abstract class BaseNowPlayingWidgetProvider : AppWidgetProvider() {

    protected abstract val small: Boolean
    protected abstract val receiverClass: Class<*>

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (appWidgetId in ids) {
            runCatching {
                val views = if (small) {
                    WidgetViews.buildSmall(context, receiverClass, appWidgetId)
                } else {
                    WidgetViews.buildLarge(context, receiverClass, appWidgetId)
                }
                manager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    /**
     * The user resized the widget: re-pick compact vs full layout for the
     * new allocation right away instead of waiting for the next track event.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        runCatching {
            val views = if (small) {
                WidgetViews.buildSmall(context, receiverClass, appWidgetId)
            } else {
                WidgetViews.buildLarge(context, receiverClass, appWidgetId)
            }
            manager.updateAppWidget(appWidgetId, views)
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

/** Small (5x1) widget. Class name kept stable so placed widgets survive the update. */
class NowPlayingWidgetReceiver : BaseNowPlayingWidgetProvider() {
    override val small: Boolean = true
    override val receiverClass: Class<*> = NowPlayingWidgetReceiver::class.java
}
