// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

import kotlinx.coroutines.flow.Flow

interface LocationClient {
    fun updates(): Flow<LocationEvent>
}

sealed interface LocationEvent {
    data class Sample(val location: LocationSample) : LocationEvent

    data class Availability(val isAvailable: Boolean) : LocationEvent

    data class ProviderEnabled(val isEnabled: Boolean) : LocationEvent

    data class Satellites(val telemetry: GnssTelemetry) : LocationEvent
}
