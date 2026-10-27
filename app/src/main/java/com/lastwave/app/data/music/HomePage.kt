/*
 * Adapted from Metrolist's InnerTube home feed architecture (GPL-3.0).
 * Reference: com.metrolist.innertube.pages.HomePage
 * Copyright (C) 2024 Metrolist Project / LastWave Authors
 * Licensed under the GNU General Public License v3.0 (GPL-3.0).
 */
package com.lastwave.app.data.music

import androidx.compose.runtime.Immutable

/**
 * Result of a YouTube Music Home feed request, containing native carousel shelves,
 * mood/activity chips, and an optional continuation token for pagination.
 */
@Immutable
data class HomePage(
    val shelves: List<HomeShelf> = emptyList(),
    val chips: List<HomeChip> = emptyList(),
    val continuationToken: String? = null,
)
