// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.ui

import org.opengpxtracker.android.location.LocationSample
import java.util.Locale

internal fun locationMetricsText(location: LocationSample?, isFresh: Boolean): String {
    return "车速 ${speedText(location, isFresh)} · 海拔 ${altitudeText(location, isFresh)}"
}

internal fun speedText(location: LocationSample?, isFresh: Boolean): String {
    if (!isFresh) return "-- km/h"
    val speed = location?.speedMetersPerSecond
        ?.takeIf { it.isFinite() && it >= 0f }
        ?.let { String.format(Locale.US, "%.1f", it * METERS_PER_SECOND_TO_KM_PER_HOUR) }
        ?: "--"
    return "$speed km/h"
}

internal fun altitudeText(location: LocationSample?, isFresh: Boolean): String {
    if (!isFresh) return "-- m"
    val altitude = location?.altitudeMeters
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.0f", it) }
        ?: "--"
    return "$altitude m"
}

private const val METERS_PER_SECOND_TO_KM_PER_HOUR = 3.6f
