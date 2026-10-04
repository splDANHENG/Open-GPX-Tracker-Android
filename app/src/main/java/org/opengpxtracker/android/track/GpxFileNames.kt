// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxFileNames {
    fun defaultName(recording: TrackRecording): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(recording.startedAtMillis))
        return "OpenGPX-$stamp-${recording.id ?: 0}.gpx"
    }

    fun normalize(input: String): String? {
        val name = input.trim()
        if (!isValidPart(name)) return null
        val completed = if (name.endsWith(".gpx", ignoreCase = true)) name else "$name.gpx"
        return completed.takeIf { it.length <= MAX_FILE_NAME_LENGTH }
    }

    fun withPlacePrefix(prefix: String, fileName: String): String? {
        val normalizedName = normalize(fileName) ?: return null
        val place = prefix.trim()
        if (place.isEmpty()) return normalizedName
        if (!isValidPart(place)) return null
        return normalize("$place-${normalizedName.dropLast(4)}.gpx")
    }

    fun isValidPlacePrefix(prefix: String): Boolean =
        prefix.isBlank() || isValidPart(prefix.trim())

    private fun isValidPart(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." &&
            value.none { it.isISOControl() || it in "/\\:*?\"<>|" }

    private const val MAX_FILE_NAME_LENGTH = 120
}
