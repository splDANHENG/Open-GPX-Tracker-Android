// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import org.opengpxtracker.android.location.LocationSample
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RecordingMode { IDLE, RECORDING, PAUSED, FINISHED }

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long,
    val timestampMillis: Long,
    val altitudeMeters: Double?,
)

data class TrackRecording(
    val id: Long? = null,
    val startedAtMillis: Long = 0L,
    val mode: RecordingMode = RecordingMode.IDLE,
    val segments: List<List<TrackPoint>> = emptyList(),
    val distanceMeters: Double = 0.0,
    val pointCount: Int = 0,
    val elapsedMillis: Long = 0L,
    val activeSinceMillis: Long? = null,
    val needsNewSegment: Boolean = true,
) {
    fun start(nowMillis: Long, wallTimeMillis: Long = 0L): TrackRecording = TrackRecording(
        startedAtMillis = wallTimeMillis,
        mode = RecordingMode.RECORDING,
        activeSinceMillis = nowMillis,
    )

    fun pause(nowMillis: Long): TrackRecording = if (mode == RecordingMode.RECORDING) {
        copy(
            mode = RecordingMode.PAUSED,
            elapsedMillis = elapsedAt(nowMillis),
            activeSinceMillis = null,
            needsNewSegment = true,
        )
    } else this

    fun resume(nowMillis: Long): TrackRecording = if (mode == RecordingMode.PAUSED || mode == RecordingMode.FINISHED) {
        copy(mode = RecordingMode.RECORDING, activeSinceMillis = nowMillis, needsNewSegment = true)
    } else this

    fun finish(nowMillis: Long): TrackRecording = if (mode == RecordingMode.RECORDING || mode == RecordingMode.PAUSED) {
        copy(
            mode = RecordingMode.FINISHED,
            elapsedMillis = elapsedAt(nowMillis),
            activeSinceMillis = null,
            needsNewSegment = true,
        )
    } else this

    fun elapsedAt(nowMillis: Long): Long = elapsedMillis +
        (activeSinceMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L)

    fun add(sample: LocationSample): TrackRecording {
        if (mode != RecordingMode.RECORDING) return this
        if (!sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0
        ) return this
        val last = segments.lastOrNull()?.lastOrNull()
        if (!needsNewSegment && last != null && last.elapsedRealtimeNanos > 0L &&
            sample.elapsedRealtimeNanos > 0L && sample.elapsedRealtimeNanos <= last.elapsedRealtimeNanos
        ) return this
        val point = TrackPoint(
            sample.latitude,
            sample.longitude,
            sample.elapsedRealtimeNanos,
            sample.timestampMillis,
            sample.altitudeMeters?.takeIf { it.isFinite() },
        )
        if (needsNewSegment || last == null) {
            return copy(
                segments = segments + listOf(listOf(point)),
                pointCount = pointCount + 1,
                needsNewSegment = false,
            )
        }

        val distance = distanceMeters(last, point)
        return copy(
            segments = segments.dropLast(1) + listOf(segments.last() + point),
            distanceMeters = distanceMeters + distance,
            pointCount = pointCount + 1,
        )
    }
}

internal fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
    val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
    val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
        sin(longitudeDelta / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
