package com.lastwave.app.widget.wave

import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context

data class WaveWidgetProviderEntry(
    val kind: WaveWidgetKind,
    val providerClass: Class<out AppWidgetProvider>,
)

/** One source of truth for updater/provider discovery. */
object WaveWidgetProviders {
    val entries: List<WaveWidgetProviderEntry> = listOf(
        WaveWidgetProviderEntry(WaveWidgetKind.STRIP, WaveStripWidgetProvider::class.java),
        WaveWidgetProviderEntry(WaveWidgetKind.GLASS, WaveGlassWidgetProvider::class.java),
        WaveWidgetProviderEntry(WaveWidgetKind.CIRCULAR, WaveCircularWidgetProvider::class.java),
        WaveWidgetProviderEntry(WaveWidgetKind.LIBRARY, WaveLibraryWidgetProvider::class.java),
    )

    fun providerClass(kind: WaveWidgetKind): Class<out AppWidgetProvider> =
        entries.first { it.kind == kind }.providerClass

    fun component(context: Context, kind: WaveWidgetKind): ComponentName =
        ComponentName(context, providerClass(kind))

    fun components(context: Context): List<Pair<WaveWidgetKind, ComponentName>> =
        entries.map { it.kind to ComponentName(context, it.providerClass) }

    fun kindFor(providerClass: Class<*>): WaveWidgetKind? =
        entries.firstOrNull { it.providerClass == providerClass }?.kind

    fun kindFor(componentName: ComponentName): WaveWidgetKind? =
        entries.firstOrNull { it.providerClass.name == componentName.className }?.kind
}
