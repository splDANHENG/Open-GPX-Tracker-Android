// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.opengpxtracker.android.track.TrackRecording
import org.opengpxtracker.android.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One user-initiated reverse lookup of the track start, cached for later exports. */
class OsmPlacePrefix(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("osm_place_prefixes", Context.MODE_PRIVATE)

    suspend fun suggest(recording: TrackRecording): String? {
        val id = recording.id ?: return null
        val start = recording.segments.firstOrNull()?.firstOrNull() ?: return null
        val cacheKey = "track_$id"
        if (preferences.contains(cacheKey)) return preferences.getString(cacheKey, "")?.ifBlank { null }

        val place = requestMutex.withLock {
            val waitMillis = (lastRequestAtMillis + MIN_REQUEST_INTERVAL_MILLIS - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            if (waitMillis > 0L) delay(waitMillis)
            lastRequestAtMillis = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { lookup(start.latitude, start.longitude) }
        }
        preferences.edit().putString(cacheKey, place.orEmpty()).apply()
        return place
    }

    private fun lookup(latitude: Double, longitude: Double): String? {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val connection = URL(
            "$BASE_URL/reverse?format=jsonv2&lat=$lat&lon=$lon&addressdetails=1&zoom=14&layer=address",
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "OSM 地名查询返回 ${connection.responseCode}"
            }
            val address = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("address") ?: return null
            return selectCityLevelName(CITY_LEVEL_KEYS.associateWith(address::optString))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://nominatim.openstreetmap.org"
        private val USER_AGENT = "OpenGPXTracker-Android/${BuildConfig.VERSION_NAME} (org.opengpxtracker.android; GPX export place prefix)"
        private const val MIN_REQUEST_INTERVAL_MILLIS = 1_000L
        private val CITY_LEVEL_KEYS = listOf("city", "town", "municipality", "village")
        private val requestMutex = Mutex()
        private var lastRequestAtMillis = 0L

        internal fun selectCityLevelName(address: Map<String, String>): String? =
            CITY_LEVEL_KEYS.asSequence()
                .mapNotNull { address[it]?.trim() }
                .firstOrNull { it.isNotEmpty() && it != "." && it != ".." &&
                    it.none { char -> char.isISOControl() || char in "/\\:*?\"<>|" } }
    }
}
