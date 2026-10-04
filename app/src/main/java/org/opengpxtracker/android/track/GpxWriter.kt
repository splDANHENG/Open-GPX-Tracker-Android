// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.util.Xml
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxWriter {
    fun write(recording: TrackRecording, output: OutputStream) {
        val xml = Xml.newSerializer()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", true)
        xml.setPrefix("", GPX_NAMESPACE)
        xml.startTag(GPX_NAMESPACE, "gpx")
        xml.attribute(null, "version", "1.1")
        xml.attribute(null, "creator", "Open GPX Tracker Android")
        xml.startTag(GPX_NAMESPACE, "trk")
        xml.startTag(GPX_NAMESPACE, "name")
        xml.text("Track ${dateFormat.format(Date(recording.startedAtMillis))}")
        xml.endTag(GPX_NAMESPACE, "name")

        for (segment in recording.segments) {
            if (segment.isEmpty()) continue
            xml.startTag(GPX_NAMESPACE, "trkseg")
            for (point in segment) {
                xml.startTag(GPX_NAMESPACE, "trkpt")
                xml.attribute(null, "lat", String.format(Locale.US, "%.8f", point.latitude))
                xml.attribute(null, "lon", String.format(Locale.US, "%.8f", point.longitude))
                point.altitudeMeters?.let {
                    xml.startTag(GPX_NAMESPACE, "ele")
                    xml.text(String.format(Locale.US, "%.2f", it))
                    xml.endTag(GPX_NAMESPACE, "ele")
                }
                // Imported points without a timestamp remain untimed on export.
                if (point.timestampMillis != 0L) {
                    xml.startTag(GPX_NAMESPACE, "time")
                    xml.text(dateFormat.format(Date(point.timestampMillis)))
                    xml.endTag(GPX_NAMESPACE, "time")
                }
                xml.endTag(GPX_NAMESPACE, "trkpt")
            }
            xml.endTag(GPX_NAMESPACE, "trkseg")
        }

        xml.endTag(GPX_NAMESPACE, "trk")
        xml.endTag(GPX_NAMESPACE, "gpx")
        xml.endDocument()
        xml.flush()
    }

    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
}
