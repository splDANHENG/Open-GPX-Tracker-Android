// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.opengpxtracker.android.location.LocationSample
import org.opengpxtracker.android.track.GpxReader
import org.opengpxtracker.android.track.GpxWriter
import org.opengpxtracker.android.track.TrackStore
import java.io.ByteArrayOutputStream

class GpxImportTest {
    @Test
    fun importPreservesSegmentsAndExcludesPauseFromDuration() {
        val track = read("""
            <trk><trkseg>
              <trkpt lat="51" lon="-2"><time>2026-10-04T10:00:00Z</time></trkpt>
              <trkpt lat="51.001" lon="-2"><time>2026-10-04T10:01:00Z</time></trkpt>
            </trkseg><trkseg>
              <trkpt lat="52" lon="-3"><time>2026-10-04T15:00:00Z</time></trkpt>
              <trkpt lat="52.001" lon="-3"><time>2026-10-04T15:02:00Z</time></trkpt>
            </trkseg></trk>
        """)
        assertEquals(listOf(2, 2), track.segments.map { it.size })
        assertEquals(180_000L, track.elapsedMillis)
        assertTrue(track.distanceMeters in 220.0..225.0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gpx-import-${SystemClock.elapsedRealtimeNanos()}.db"
        try {
            TrackStore(context, name).use { store ->
                val id = store.import(track)
                val continued = requireNotNull(store.load(id)).resume(1_000L).add(
                    LocationSample(53.0, -4.0, 5f, 20.0, null, System.currentTimeMillis(), 1L),
                )
                store.save(continued, continued.segments.last().last(), continued.elapsedAt(2_000L))
                val loaded = requireNotNull(store.load(id))
                assertEquals(listOf(2, 2, 1), loaded.segments.map { it.size })
                assertEquals(5, loaded.pointCount)
                assertEquals(track.distanceMeters, loaded.distanceMeters, 0.001)
                assertEquals(181_000L, loaded.elapsedMillis)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun missingTimesRemainMissingAndMicrosecondsAreParsedCorrectly() {
        val track = read("""
            <trk><trkseg>
              <trkpt lat="51" lon="-2"><ele>123.4</ele></trkpt>
              <trkpt lat="51.001" lon="-2"><time>2026-10-04T11:00:00.123456+01:00</time></trkpt>
              <trkpt lat="51.002" lon="-2"><time>2026-10-04T10:00:00.223Z</time></trkpt>
            </trkseg></trk>
        """)
        assertEquals(0L, track.segments[0][0].timestampMillis)
        assertEquals(100L, track.elapsedMillis)
        val output = ByteArrayOutputStream()
        GpxWriter.write(track, output)
        val roundTrip = GpxReader.read(output.toByteArray().inputStream())
        assertEquals(track.segments, roundTrip.segments)
        assertEquals(2, Regex("<time>").findAll(output.toString("UTF-8")).count())
    }

    @Test
    fun utf16AndExtensionTagsDoNotCorruptTheImportedTrack() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:ext="urn:test" version="1.1" creator="test">
              <trk><trkseg><trkpt lat="51" lon="-2"><extensions>
                <ext:time>invalid</ext:time><ext:trkpt lat="999" lon="999"/>
              </extensions></trkpt></trkseg></trk>
            </gpx>""".trimIndent()
        val track = GpxReader.read(xml.toByteArray(Charsets.UTF_16).inputStream())
        assertEquals(1, track.pointCount)
        assertEquals(0L, track.segments[0][0].timestampMillis)
    }

    @Test
    fun malformedCoordinateAndInvalidDateAreRejected() {
        expectRejected { read("<trk><trkseg><trkpt lat=\"NaN\" lon=\"-2\"/></trkseg></trk>") }
        expectRejected { read("<trk><trkseg><trkpt lat=\"51\" lon=\"-2\"><time>2026-02-30T10:00:00Z</time></trkpt></trkseg></trk>") }
        expectRejected { GpxReader.read("<other><trk><trkseg><trkpt lat=\"51\" lon=\"-2\"/></trkseg></trk></other>".byteInputStream()) }
    }

    @Test
    fun dtdIsRejectedBeforeReadingEntityContent() {
        expectRejected {
            GpxReader.read("""<!DOCTYPE gpx [<!ENTITY example "123">]>
                <gpx><trk><trkseg><trkpt lat="51" lon="-2"><ele>&example;</ele></trkpt></trkseg></trk></gpx>
            """.trimIndent().byteInputStream())
        }
    }

    private fun read(content: String) = GpxReader.read(
        "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\" creator=\"test\">$content</gpx>".byteInputStream(),
    )

    private fun expectRejected(action: () -> Unit) {
        try { action(); fail("Invalid GPX must be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
}
