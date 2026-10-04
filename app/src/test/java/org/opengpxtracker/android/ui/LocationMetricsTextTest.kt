// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opengpxtracker.android.location.LocationSample

class LocationMetricsTextTest {
    @Test
    fun convertsGroundSpeedAndShowsAltitude() {
        assertEquals(
            "车速 36.0 km/h · 海拔 123 m",
            locationMetricsText(sample(speed = 10f, altitude = 123.4), isFresh = true),
        )
    }

    @Test
    fun staleOrUnavailableValuesDoNotShowThePreviousReading() {
        val location = sample(speed = 10f, altitude = -430.0)
        assertEquals("车速 -- km/h · 海拔 -- m", locationMetricsText(location, isFresh = false))
        assertEquals("车速 -- km/h · 海拔 -- m", locationMetricsText(null, isFresh = false))
        assertEquals(
            "车速 -- km/h · 海拔 -- m",
            locationMetricsText(sample(speed = null, altitude = null), isFresh = true),
        )
    }

    @Test
    fun belowSeaLevelAltitudeAndStationarySpeedRemainValid() {
        assertEquals(
            "车速 0.0 km/h · 海拔 -430 m",
            locationMetricsText(sample(speed = 0f, altitude = -430.0), isFresh = true),
        )
    }

    private fun sample(speed: Float?, altitude: Double?) = LocationSample(
        latitude = 42.0,
        longitude = 118.0,
        accuracyMeters = 5f,
        altitudeMeters = altitude,
        speedMetersPerSecond = speed,
        timestampMillis = 0L,
        elapsedRealtimeNanos = 0L,
    )
}
