package com.lastwave.app.widget.wave

import androidx.annotation.LayoutRes
import com.lastwave.app.R

/** The six deliberately distinct Wave widget surfaces exposed in the picker. */
enum class WaveWidgetKind(
    @LayoutRes val layoutRes: Int,
    val targetCellWidth: Int,
    val targetCellHeight: Int,
) {
    TURNTABLE(R.layout.widget_wave_turntable, 1, 1),
    VINYL(R.layout.widget_wave_vinyl, 2, 2),
    DECK(R.layout.widget_wave_deck, 4, 2),
    STAGE(R.layout.widget_wave_stage, 4, 4),
    PILL(R.layout.widget_wave_pill, 2, 1),
    STRIP(R.layout.widget_wave_strip, 4, 1),
}
