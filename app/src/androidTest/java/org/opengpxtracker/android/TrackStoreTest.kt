// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android

import android.os.SystemClock
import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opengpxtracker.android.location.LocationSample
import org.opengpxtracker.android.track.GpxWriter
import org.opengpxtracker.android.track.DefaultGpxExporter
import org.opengpxtracker.android.track.GpxFileRepository
import org.opengpxtracker.android.track.GpxFileNames
import org.opengpxtracker.android.track.RecordingMode
import org.opengpxtracker.android.track.TrackRecording
import org.opengpxtracker.android.track.TrackStore

@RunWith(AndroidJUnit4::class)
class TrackStoreTest {
    @Test
    fun interruptedTrackReopensAndExportsValidSegmentedGpx() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "track-store-test-${SystemClock.elapsedRealtimeNanos()}.db"
        val startedAt = 1_790_000_000_000L
        val store = TrackStore(context, databaseName)
        try {
            val id = store.create(startedAt)
            var recording = TrackRecording().start(1_000L, startedAt).copy(id = id)
            recording = recording.add(sample(42.261568, 118.908944, 1L, startedAt))
            store.save(recording, recording.segments.last().last(), 1_000L)
            recording = recording.add(sample(42.257423, 118.912875, 2L, startedAt + 2_000L))
            store.save(recording, recording.segments.last().last(), 3_000L)
            recording = recording.pause(4_000L).resume(5_000L)
                .add(sample(42.247125, 118.918796, 3L, startedAt + 20_000L))
            store.save(recording, recording.segments.last().last(), 20_000L)
            assertTrue(store.savePlaceName(id, "Bristol"))
            store.close()

            TrackStore(context, databaseName).use { reopened ->
                reopened.recoverInterruptedRecording()
                val loaded = requireNotNull(reopened.load(id))
                assertEquals(RecordingMode.PAUSED, loaded.mode)
                assertEquals(listOf(2, 1), loaded.segments.map { it.size })
                assertEquals(3, loaded.pointCount)
                assertEquals(20_000L, loaded.elapsedMillis)
                assertTrue(loaded.distanceMeters > 500.0)
                assertEquals(1, reopened.list().size)
                assertEquals("Bristol", reopened.list().first().placeName)

                val output = ByteArrayOutputStream()
                GpxWriter.write(loaded, output)
                val document = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                }.newDocumentBuilder().parse(ByteArrayInputStream(output.toByteArray()))
                val namespace = "http://www.topografix.com/GPX/1/1"
                assertEquals("gpx", document.documentElement.localName)
                assertEquals("1.1", document.documentElement.getAttribute("version"))
                assertEquals(2, document.getElementsByTagNameNS(namespace, "trkseg").length)
                assertEquals(3, document.getElementsByTagNameNS(namespace, "trkpt").length)
                assertEquals(3, document.getElementsByTagNameNS(namespace, "time").length)
                assertNotNull(document.getElementsByTagNameNS(namespace, "ele").item(0))

                assertTrue(reopened.delete(id))
                assertNull(reopened.load(id))
                assertTrue(reopened.list().isEmpty())
                assertFalse(reopened.delete(id))
            }
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionOneHistoryMigratesWithoutLosingTracks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "track-migration-test-${SystemClock.elapsedRealtimeNanos()}.db"
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL(
                    "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "started_at INTEGER NOT NULL, mode TEXT NOT NULL, " +
                        "elapsed_ms INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0, " +
                        "point_count INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("INSERT INTO tracks (id, started_at, mode) VALUES (7, 1000, 'FINISHED')")
                db.version = 1
            }
            TrackStore(context, databaseName).use { store ->
                assertEquals(1, store.list().size)
                assertNull(store.list().first().placeName)
                assertTrue(store.savePlaceName(7L, "Bath"))
                assertEquals("Bath", store.list().first().placeName)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun defaultExportCreatesVisibleGpxInDocumentsFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startedAt = 1_790_000_000_000L
        val recording = TrackRecording()
            .start(1_000L, startedAt)
            .copy(id = SystemClock.elapsedRealtimeNanos())
            .add(sample(42.261568, 118.908944, 1L, startedAt))
        val uri = DefaultGpxExporter.export(context, recording)
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            ).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals("Documents/OpenGPXTracker/", cursor.getString(0))
            }
            val xml = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input).bufferedReader().readText()
            }
            assertTrue(xml.contains("<trkpt"))
            val repository = GpxFileRepository(context)
            val saved = requireNotNull(repository.list().firstOrNull { it.uri == uri })
            assertTrue(saved.sizeBytes > 0)
            assertTrue(repository.delete(saved))
            assertFalse(repository.list().any { it.uri == uri })
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun customExportNameIsUsedInDocumentsFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recording = TrackRecording()
            .start(1_000L, 1_790_000_000_000L)
            .copy(id = SystemClock.elapsedRealtimeNanos())
            .finish(2_000L)
        val requestedName = "test-${recording.id}"
        val uri = DefaultGpxExporter.export(context, recording, requestedName)
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            ).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals("$requestedName.gpx", cursor.getString(0))
                assertEquals("Documents/OpenGPXTracker/", cursor.getString(1))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun exportFileNameValidationPreservesUserChoice() {
        assertEquals("ride.gpx", GpxFileNames.normalize(" ride "))
        assertEquals("ride.GPX", GpxFileNames.normalize("ride.GPX"))
        assertNull(GpxFileNames.normalize("../ride"))
        assertNull(GpxFileNames.normalize(" "))
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun emptyTrackCanStillExportValidGpx() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recording = TrackRecording()
            .start(1_000L, 1_790_000_000_000L)
            .copy(id = SystemClock.elapsedRealtimeNanos())
            .finish(2_000L)
        val uri = DefaultGpxExporter.export(context, recording)
        try {
            val xml = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input).bufferedReader().readText()
            }
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val namespace = "http://www.topografix.com/GPX/1/1"
            assertEquals("gpx", document.documentElement.localName)
            assertEquals(1, document.getElementsByTagNameNS(namespace, "trk").length)
            assertEquals(0, document.getElementsByTagNameNS(namespace, "trkpt").length)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun sample(latitude: Double, longitude: Double, nanos: Long, time: Long) =
        LocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 5f,
            altitudeMeters = 555.0,
            speedMetersPerSecond = null,
            timestampMillis = time,
            elapsedRealtimeNanos = nanos,
        )
}
