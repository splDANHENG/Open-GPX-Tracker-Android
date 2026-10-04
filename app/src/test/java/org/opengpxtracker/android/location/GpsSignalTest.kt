// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsSignalTest {
    private val sample = LocationSample(
        latitude = 42.0,
        longitude = 118.0,
        accuracyMeters = 5f,
        altitudeMeters = null,
        speedMetersPerSecond = null,
        timestampMillis = 0L,
        elapsedRealtimeNanos = 0L,
    )

    @Test
    fun disabledProviderNeverShowsOldAccuracyAsLiveSignal() {
        assertEquals(
            GpsSignalLevel.DISABLED,
            assessGpsSignal(sample, 500L, true, false, null),
        )
    }

    @Test
    fun staleLocationIsLostEvenWhenItsAccuracyWasGood() {
        assertEquals(
            GpsSignalLevel.LOST,
            assessGpsSignal(
                sample,
                LOCATION_STALE_AFTER_MILLIS + 1L,
                true,
                true,
                GnssTelemetry(12, 8, 36f),
            ),
        )
    }

    @Test
    fun satelliteCn0AndFixCountDetermineLiveStrength() {
        assertEquals(
            GpsSignalLevel.STRONG,
            assessGpsSignal(sample, 500L, true, true, GnssTelemetry(12, 8, 36f)),
        )
        assertEquals(
            GpsSignalLevel.MODERATE,
            assessGpsSignal(sample, 500L, true, true, GnssTelemetry(8, 3, 24f)),
        )
        assertEquals(
            GpsSignalLevel.WEAK,
            assessGpsSignal(sample, 500L, true, true, GnssTelemetry(5, 1, 16f)),
        )
    }
}
