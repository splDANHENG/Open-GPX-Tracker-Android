// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

data class GnssTelemetry(
    val visibleSatellites: Int,
    val usedSatellites: Int,
    val averageCn0DbHz: Float?,
)

enum class GpsSignalLevel {
    SEARCHING,
    STRONG,
    MODERATE,
    WEAK,
    LOST,
    DISABLED,
}

fun assessGpsSignal(
    location: LocationSample?,
    locationAgeMillis: Long?,
    fusedLocationAvailable: Boolean,
    gpsProviderEnabled: Boolean,
    gnssTelemetry: GnssTelemetry?,
): GpsSignalLevel {
    if (!gpsProviderEnabled) return GpsSignalLevel.DISABLED
    if (location == null || locationAgeMillis == null) return GpsSignalLevel.SEARCHING
    if (!fusedLocationAvailable || locationAgeMillis > LOCATION_STALE_AFTER_MILLIS) {
        return GpsSignalLevel.LOST
    }

    if (gnssTelemetry != null) {
        val cn0 = gnssTelemetry.averageCn0DbHz ?: 0f
        return when {
            gnssTelemetry.usedSatellites >= 4 && cn0 >= 30f -> GpsSignalLevel.STRONG
            gnssTelemetry.usedSatellites >= 3 && cn0 >= 20f -> GpsSignalLevel.MODERATE
            else -> GpsSignalLevel.WEAK
        }
    }

    // Some devices and emulators do not expose satellite telemetry. Accuracy is
    // only a fallback here; freshness and availability still decide signal loss.
    return when {
        location.accuracyMeters <= 10f -> GpsSignalLevel.STRONG
        location.accuracyMeters <= 30f -> GpsSignalLevel.MODERATE
        else -> GpsSignalLevel.WEAK
    }
}

const val LOCATION_STALE_AFTER_MILLIS = 10_000L
