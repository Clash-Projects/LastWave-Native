package com.lastwave.app.widget

import androidx.glance.appwidget.GlanceAppWidget

class LargeNowPlayingWidgetReceiver : LastWaveWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeNowPlayingWidget()
}
