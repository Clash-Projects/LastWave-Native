/*
 * Adapted from Metrolist's InnerTube home feed architecture (GPL-3.0).
 * Reference: com.metrolist.innertube.pages.HomePage.Chip
 * Copyright (C) 2024 Metrolist Project / LastWave Authors
 * Licensed under the GNU General Public License v3.0 (GPL-3.0).
 */
package com.lastwave.app.data.music

import androidx.compose.runtime.Immutable

/**
 * Represents a mood/activity chip at the top of YouTube Music Home
 * (e.g., Relax, Energize, Workout, Commute, Focus).
 *
 * Note: [isSelected] is deliberately not stored in this data model;
 * UI selection state is managed independently in the ViewModel.
 */
@Immutable
data class HomeChip(
    val title: String,
    val params: String? = null,
    val browseId: String? = null,
)
