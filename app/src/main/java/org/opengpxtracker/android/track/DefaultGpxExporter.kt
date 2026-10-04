// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException

object DefaultGpxExporter {
    const val DISPLAY_DIRECTORY = "Documents/OpenGPXTracker"

    @RequiresApi(Build.VERSION_CODES.Q)
    fun export(
        context: Context,
        recording: TrackRecording,
        fileName: String = GpxFileNames.defaultName(recording),
    ): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "默认目录导出需要 Android 10 或更高版本"
        }
        val name = requireNotNull(GpxFileNames.normalize(fileName)) { "GPX 文件名无效" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/OpenGPXTracker/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: throw IOException("无法创建 GPX 文件")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入 GPX 文件")
            output.use { GpxWriter.write(recording, it) }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("无法完成 GPX 文件保存")
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
