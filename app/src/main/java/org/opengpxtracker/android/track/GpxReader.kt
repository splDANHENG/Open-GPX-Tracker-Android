// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads GPX tracks; continuation starts a new segment without altering imported points. */
object GpxReader {
    fun read(input: InputStream): TrackRecording {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // Let the XML declaration determine the encoding, including UTF-16 GPX files.
        parser.setInput(LimitedInputStream(input), null)
        val segments = mutableListOf<List<TrackPoint>>()
        var segment: MutableList<TrackPoint>? = null
        var point: TrackPoint? = null
        var inTrack = false
        var rootSeen = false
        var namespace: String? = null
        var pointCount = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> error("不支持包含 DTD 的 GPX 文件")
                XmlPullParser.START_TAG -> {
                    if (parser.depth == 1) {
                        require(parser.name == "gpx" && parser.namespace in GPX_NAMESPACES) { "不是有效的 GPX 文件" }
                        rootSeen = true
                        namespace = parser.namespace
                    } else if (parser.namespace == namespace) {
                        when {
                            parser.depth == 2 && parser.name == "trk" -> inTrack = true
                            parser.depth == 3 && parser.name == "trkseg" && inTrack -> segment = mutableListOf()
                            parser.depth == 4 && parser.name == "trkpt" && segment != null -> {
                                require(pointCount < MAX_POINTS) { "GPX 轨迹点过多（最多 500000 个点）" }
                                val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                require(latitude != null && longitude != null &&
                                    latitude.isFinite() && longitude.isFinite() &&
                                    latitude in -90.0..90.0 && longitude in -180.0..180.0
                                ) { "GPX 轨迹点坐标无效" }
                                point = TrackPoint(latitude, longitude, 0L, 0L, null)
                            }
                            parser.depth == 5 && parser.name == "ele" && point != null -> {
                                val altitude = parser.nextText().trim().toDoubleOrNull()
                                require(altitude != null && altitude.isFinite()) { "GPX 海拔数据无效" }
                                point = point.copy(altitudeMeters = altitude)
                            }
                            parser.depth == 5 && parser.name == "time" && point != null -> {
                                val time = parseTime(parser.nextText().trim())
                                require(time != null) { "GPX 时间格式无效" }
                                point = point.copy(timestampMillis = time)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.namespace == namespace) {
                    when {
                        parser.depth == 4 && parser.name == "trkpt" && segment != null -> {
                            point?.let { segment.add(it); pointCount++ }
                            point = null
                        }
                        parser.depth == 3 && parser.name == "trkseg" && segment != null -> {
                            if (segment.isNotEmpty()) segments.add(segment)
                            segment = null
                        }
                        parser.depth == 2 && parser.name == "trk" -> inTrack = false
                    }
                }
            }
            // next() hides DTD events, so use nextToken() to reject declarations explicitly.
            event = parser.nextToken()
        }
        require(rootSeen && segments.isNotEmpty()) { "GPX 文件没有可用的轨迹点" }
        var startedAt: Long? = null
        var elapsed = 0L
        var distance = 0.0
        for (saved in segments) {
            var firstTime: Long? = null
            var lastTime: Long? = null
            var previous: TrackPoint? = null
            for (savedPoint in saved) {
                if (savedPoint.timestampMillis != 0L) {
                    if (startedAt == null) startedAt = savedPoint.timestampMillis
                    if (firstTime == null) firstTime = savedPoint.timestampMillis
                    lastTime = savedPoint.timestampMillis
                }
                previous?.let { distance += distanceMeters(it, savedPoint) }
                previous = savedPoint
            }
            if (firstTime != null && lastTime != null) elapsed += (lastTime - firstTime).coerceAtLeast(0L)
        }
        return TrackRecording(
            startedAtMillis = startedAt ?: System.currentTimeMillis(),
            mode = RecordingMode.FINISHED,
            segments = segments,
            distanceMeters = distance,
            pointCount = pointCount,
            elapsedMillis = elapsed,
        )
    }

    private fun parseTime(value: String): Long? {
        // GPX permits arbitrary fractional precision; the recording model stores milliseconds.
        val match = TIME_PATTERN.matchEntire(value) ?: return null
        val fraction = match.groupValues[2].takeIf { it.isNotEmpty() }
        val normalized = match.groupValues[1] +
            (fraction?.let { ".${it.take(3).padEnd(3, '0')}" } ?: "") + match.groupValues[3]
        val format = SimpleDateFormat(
            if (fraction != null) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val position = ParsePosition(0)
        return format.parse(normalized, position)?.takeIf { position.index == normalized.length }?.time
    }

    private val TIME_PATTERN = Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})")
    private val GPX_NAMESPACES = setOf("", "http://www.topografix.com/GPX/1/0", "http://www.topografix.com/GPX/1/1")
    private const val MAX_POINTS = 500_000
    private const val MAX_BYTES = 50L * 1024L * 1024L

    private class LimitedInputStream(input: InputStream) : FilterInputStream(input) {
        private var bytesRead = 0L
        override fun read(): Int = super.read().also { if (it >= 0) checkBytes(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) checkBytes(it) }
        private fun checkBytes(count: Int) {
            bytesRead += count
            require(bytesRead <= MAX_BYTES) { "GPX 文件超过 50 MB" }
        }
    }
}
