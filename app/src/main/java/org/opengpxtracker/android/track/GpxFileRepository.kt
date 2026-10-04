// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

data class GpxSavedFile(val uri: Uri, val name: String, val sizeBytes: Long)

/** GPX files exported into this app's default public Documents folder. */
class GpxFileRepository(private val context: Context) {
    fun list(): List<GpxSavedFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val result = mutableListOf<GpxSavedFile>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${Environment.DIRECTORY_DOCUMENTS}/OpenGPXTracker/"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(path, "%.gpx"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += GpxSavedFile(
                    ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1), cursor.getLong(2),
                )
            }
        }
        return result
    }

    fun delete(file: GpxSavedFile): Boolean =
        context.contentResolver.delete(file.uri, null, null) > 0
}
