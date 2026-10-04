// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opengpxtracker.android.location.LocationSample

class TrackRecordingTest {
    @Test
    fun repeatedOrOutOfOrderFixesAndInvalidCoordinatesAreRejected() {
        val first = sample(42.0, 118.0, 10L)
        val recording = TrackRecording().start(0L).add(first)
        assertEquals(recording, recording.add(first))
        assertEquals(recording, recording.add(sample(42.1, 118.1, 9L)))
        assertEquals(recording, recording.add(sample(Double.NaN, 118.1, 11L)))
        assertEquals(recording, recording.add(sample(42.1, 181.0, 11L)))
    }

    @Test
    fun signalGapContinuesCurrentSegmentButManualPauseStartsNewSegment() {
        val first = sample(42.261568, 118.908944, 1L)
        val second = sample(42.257423, 118.912875, 2L)
        val afterGap = sample(42.247125, 118.918796, 3L)
        val afterResume = sample(42.231560, 118.927025, 4L)

        val recording = TrackRecording().start(1_000L)
            .add(first)
            .add(second)
            .add(afterGap)
            .pause(5_000L)
            .resume(9_000L)
            .add(afterResume)
            .finish(11_000L)

        assertEquals(RecordingMode.FINISHED, recording.mode)
        assertEquals(listOf(3, 1), recording.segments.map { it.size })
        assertEquals(4, recording.pointCount)
        assertEquals(6_000L, recording.elapsedMillis)
        assertTrue(recording.distanceMeters in 1_700.0..1_900.0)
    }

    @Test
    fun weakFixesAreStillRecordedWithoutAnExtraAccuracyOrDistanceGate() {
        val first = sample(42.0, 118.0, 1L)
        val weakFix = sample(42.0, 118.000001, 20_000_000_000L)
            .copy(accuracyMeters = 250f)

        val recording = TrackRecording().start(0L).add(first).add(weakFix)

        assertEquals(listOf(2), recording.segments.map { it.size })
        assertEquals(2, recording.pointCount)
    }

    @Test
    fun pausedRecordingRejectsPointsAndNewSessionClearsPreviousTrack() {
        val first = sample(42.0, 118.0, 1L)
        val paused = TrackRecording().start(0L).add(first).pause(1_000L)

        assertEquals(paused, paused.add(sample(42.1, 118.1, 2L)))
        assertEquals(0, paused.start(2_000L).pointCount)
    }

    @Test
    fun resumedTrackAcceptsNewElapsedClockAfterDeviceReboot() {
        val previous = TrackRecording().start(0L)
            .add(sample(42.0, 118.0, 100_000_000_000L))
            .pause(1_000L)
            .resume(2_000L)

        val resumed = previous.add(sample(42.1, 118.1, 1_000_000_000L))
        assertEquals(listOf(1, 1), resumed.segments.map { it.size })
        assertEquals(2, resumed.pointCount)
    }

    @Test
    fun finishedHistoryTrackCanContinueInANewSegment() {
        val finished = TrackRecording().start(1_000L)
            .add(sample(42.0, 118.0, 1L))
            .finish(3_000L)

        val continued = finished.resume(10_000L)
            .add(sample(42.1, 118.1, 10L))

        assertEquals(RecordingMode.RECORDING, continued.mode)
        assertEquals(listOf(1, 1), continued.segments.map { it.size })
        assertEquals(0.0, continued.distanceMeters, 0.001)
        assertEquals(2_000L, continued.elapsedMillis)
    }

    private fun sample(latitude: Double, longitude: Double, elapsedNanos: Long) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 5f,
        altitudeMeters = null,
        speedMetersPerSecond = null,
        timestampMillis = elapsedNanos,
        elapsedRealtimeNanos = elapsedNanos,
    )
}
