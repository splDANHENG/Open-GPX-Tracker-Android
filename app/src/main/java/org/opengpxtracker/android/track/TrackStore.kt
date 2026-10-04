// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TrackSummary(
    val id: Long,
    val startedAtMillis: Long,
    val mode: RecordingMode,
    val pointCount: Int,
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val placeName: String? = null,
)

class TrackStore(context: Context, databaseName: String = "tracks.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                mode TEXT NOT NULL,
                elapsed_ms INTEGER NOT NULL DEFAULT 0,
                distance_m REAL NOT NULL DEFAULT 0,
                point_count INTEGER NOT NULL DEFAULT 0,
                place_name TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                segment_index INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                elapsed_nanos INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                altitude_m REAL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX points_track_order ON points(track_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN place_name TEXT")
        } else {
            error("Unsupported tracks database version: $oldVersion → $newVersion")
        }
    }

    fun recoverInterruptedRecording() {
        writableDatabase.execSQL("UPDATE tracks SET mode = 'PAUSED' WHERE mode = 'RECORDING'")
    }

    fun create(startedAtMillis: Long): Long = writableDatabase.insertOrThrow(
        "tracks",
        null,
        ContentValues().apply {
            put("started_at", startedAtMillis)
            put("mode", RecordingMode.RECORDING.name)
        },
    )

    fun import(recording: TrackRecording): Long {
        require(recording.pointCount > 0) { "GPX 文件没有轨迹点" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("tracks", null, ContentValues().apply {
                put("started_at", recording.startedAtMillis)
                put("mode", RecordingMode.FINISHED.name)
                put("elapsed_ms", recording.elapsedMillis)
                put("distance_m", recording.distanceMeters)
                put("point_count", recording.pointCount)
            })
            recording.segments.forEachIndexed { segmentIndex, segment ->
                segment.forEach { point ->
                    db.insertOrThrow("points", null, ContentValues().apply {
                        put("track_id", id)
                        put("segment_index", segmentIndex)
                        put("latitude", point.latitude)
                        put("longitude", point.longitude)
                        put("elapsed_nanos", point.elapsedRealtimeNanos)
                        put("timestamp_ms", point.timestampMillis)
                        point.altitudeMeters?.let { put("altitude_m", it) }
                    })
                }
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun save(recording: TrackRecording, addedPoint: TrackPoint?, elapsedMillis: Long) {
        val id = requireNotNull(recording.id)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (addedPoint != null) {
                db.insertOrThrow(
                    "points",
                    null,
                    ContentValues().apply {
                        put("track_id", id)
                        put("segment_index", recording.segments.lastIndex)
                        put("latitude", addedPoint.latitude)
                        put("longitude", addedPoint.longitude)
                        put("elapsed_nanos", addedPoint.elapsedRealtimeNanos)
                        put("timestamp_ms", addedPoint.timestampMillis)
                        if (addedPoint.altitudeMeters != null) put("altitude_m", addedPoint.altitudeMeters)
                    },
                )
            }
            db.update(
                "tracks",
                ContentValues().apply {
                    put("mode", recording.mode.name)
                    put("elapsed_ms", elapsedMillis)
                    put("distance_m", recording.distanceMeters)
                    put("point_count", recording.pointCount)
                },
                "id = ?",
                arrayOf(id.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveElapsed(id: Long, elapsedMillis: Long) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("elapsed_ms", elapsedMillis) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun savePlaceName(id: Long, placeName: String?): Boolean =
        writableDatabase.update(
            "tracks",
            ContentValues().apply {
                if (placeName.isNullOrBlank()) putNull("place_name")
                else put("place_name", placeName.trim())
            },
            "id = ?",
            arrayOf(id.toString()),
        ) == 1

    fun list(): List<TrackSummary> {
        val tracks = mutableListOf<TrackSummary>()
        readableDatabase.query(
            "tracks",
            arrayOf("id", "started_at", "mode", "point_count", "distance_m", "elapsed_ms", "place_name"),
            null, null, null, null, "started_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tracks += TrackSummary(
                    id = cursor.getLong(0),
                    startedAtMillis = cursor.getLong(1),
                    mode = RecordingMode.valueOf(cursor.getString(2)),
                    pointCount = cursor.getInt(3),
                    distanceMeters = cursor.getDouble(4),
                    elapsedMillis = cursor.getLong(5),
                    placeName = if (cursor.isNull(6)) null else cursor.getString(6),
                )
            }
        }
        return tracks
    }

    fun delete(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", "track_id = ?", arrayOf(id.toString()))
            val deleted = db.delete("tracks", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return deleted == 1
        } finally {
            db.endTransaction()
        }
    }

    fun load(id: Long): TrackRecording? {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val recording = loadSnapshot(db, id)
            db.setTransactionSuccessful()
            return recording
        } finally {
            db.endTransaction()
        }
    }

    private fun loadSnapshot(db: SQLiteDatabase, id: Long): TrackRecording? {
        var summary: TrackSummary? = null
        db.query(
            "tracks",
            arrayOf("id", "started_at", "mode", "point_count", "distance_m", "elapsed_ms", "place_name"),
            "id = ?", arrayOf(id.toString()), null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                summary = TrackSummary(
                    id = cursor.getLong(0),
                    startedAtMillis = cursor.getLong(1),
                    mode = RecordingMode.valueOf(cursor.getString(2)),
                    pointCount = cursor.getInt(3),
                    distanceMeters = cursor.getDouble(4),
                    elapsedMillis = cursor.getLong(5),
                    placeName = if (cursor.isNull(6)) null else cursor.getString(6),
                )
            }
        }
        val track = summary ?: return null
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var currentSegmentIndex = -1
        db.query(
            "points",
            arrayOf("segment_index", "latitude", "longitude", "elapsed_nanos", "timestamp_ms", "altitude_m"),
            "track_id = ?", arrayOf(id.toString()), null, null, "id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val segmentIndex = cursor.getInt(0)
                if (segmentIndex != currentSegmentIndex) {
                    segments += mutableListOf<TrackPoint>()
                    currentSegmentIndex = segmentIndex
                }
                segments.last() += TrackPoint(
                    latitude = cursor.getDouble(1),
                    longitude = cursor.getDouble(2),
                    elapsedRealtimeNanos = cursor.getLong(3),
                    timestampMillis = cursor.getLong(4),
                    altitudeMeters = if (cursor.isNull(5)) null else cursor.getDouble(5),
                )
            }
        }
        return TrackRecording(
            id = track.id,
            startedAtMillis = track.startedAtMillis,
            mode = track.mode,
            segments = segments,
            distanceMeters = track.distanceMeters,
            pointCount = track.pointCount,
            elapsedMillis = track.elapsedMillis,
            needsNewSegment = true,
        )
    }

    private companion object {
        const val DATABASE_VERSION = 2
    }
}
