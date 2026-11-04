package com.lastwave.app.widget.wave

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared lifecycle plumbing; each picker entry remains its own provider class. */
abstract class WaveWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        refresh(context, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        refresh(context, null)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        refresh(context, newWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val applicationContext = context.applicationContext
        PROVIDER_SCOPE.launch {
            if (!WaveWidgetUpdater.widgetsExist(applicationContext)) {
                WaveWidgetUpdater.stopTicker()
            }
        }
    }

    private fun refresh(context: Context, ids: IntArray?) {
        val applicationContext = context.applicationContext
        PROVIDER_SCOPE.launch {
            runCatching {
                if (ids == null) {
                    WaveWidgetUpdater.refresh(applicationContext)
                } else {
                    WaveWidgetUpdater.refresh(applicationContext, ids)
                }
            }
        }
    }

    private companion object {
        val PROVIDER_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class WaveStripWidgetProvider : WaveWidgetProvider()
class WaveGlassWidgetProvider : WaveWidgetProvider()
class WaveCircularWidgetProvider : WaveWidgetProvider()
class WaveLibraryWidgetProvider : WaveWidgetProvider()
