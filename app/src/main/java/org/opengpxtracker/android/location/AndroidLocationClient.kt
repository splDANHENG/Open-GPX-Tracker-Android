// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Uses Android's location providers without bundling a proprietary location SDK. */
class AndroidLocationClient(context: Context) : LocationClient {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission", "NewApi")
    override fun updates(): Flow<LocationEvent> = callbackFlow {
        var subscribedProvider: String? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
                    !location.longitude.isFinite() || location.longitude !in -180.0..180.0
                ) return
                trySend(LocationEvent.Availability(true))
                trySend(LocationEvent.Sample(LocationSample(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    altitudeMeters = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            location.hasMslAltitude() -> location.mslAltitudeMeters
                        location.hasAltitude() -> location.altitude
                        else -> null
                    },
                    speedMetersPerSecond = location.takeIf { it.hasSpeed() }?.speed,
                    timestampMillis = location.time,
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                )))
            }

            override fun onProviderDisabled(provider: String) {
                trySend(LocationEvent.ProviderEnabled(isGpsProviderEnabled()))
                if (provider == subscribedProvider) trySend(LocationEvent.Availability(false))
            }

            override fun onProviderEnabled(provider: String) {
                trySend(LocationEvent.ProviderEnabled(isGpsProviderEnabled()))
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                if (provider == subscribedProvider) {
                    trySend(LocationEvent.Availability(status == LocationProvider.AVAILABLE))
                }
            }
        }

        fun subscribe() {
            val precise = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val available = locationManager.allProviders
            // Android 12+ exposes its own fused provider. Older/AOSP devices use GPS directly.
            val provider = when {
                precise && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    LocationManager.FUSED_PROVIDER in available -> LocationManager.FUSED_PROVIDER
                precise && LocationManager.GPS_PROVIDER in available -> LocationManager.GPS_PROVIDER
                LocationManager.NETWORK_PROVIDER in available -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == subscribedProvider) return
            locationManager.removeUpdates(listener)
            subscribedProvider = provider
            if (provider == null) {
                trySend(LocationEvent.Availability(false))
            } else {
                locationManager.requestLocationUpdates(provider, 2_000L, 2f, listener, Looper.getMainLooper())
            }
        }

        val providerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(LocationEvent.ProviderEnabled(isGpsProviderEnabled()))
                runCatching { subscribe() }.onFailure { close(it) }
            }
        }
        ContextCompat.registerReceiver(appContext, providerReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        val gnssCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onStarted() { trySend(LocationEvent.Satellites(GnssTelemetry(0, 0, null))) }
                override fun onStopped() { trySend(LocationEvent.Satellites(GnssTelemetry(0, 0, null))) }
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    var total = 0f
                    var count = 0
                    for (index in 0 until status.satelliteCount) {
                        if (status.usedInFix(index)) used++
                        status.getCn0DbHz(index).takeIf { it > 0f }?.let { total += it; count++ }
                    }
                    trySend(LocationEvent.Satellites(GnssTelemetry(status.satelliteCount, used,
                        if (count > 0) total / count else null)))
                }
            }
        } else null
        val gnssRegistered = gnssCallback?.let {
            runCatching { locationManager.registerGnssStatusCallback(it, Handler(Looper.getMainLooper())) }
                .getOrDefault(false)
        } ?: false
        trySend(LocationEvent.ProviderEnabled(isGpsProviderEnabled()))
        runCatching { subscribe() }.onFailure { close(it) }
        awaitClose {
            runCatching { locationManager.removeUpdates(listener) }
            runCatching { appContext.unregisterReceiver(providerReceiver) }
            if (gnssRegistered && gnssCallback != null) {
                runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            }
        }
    }

    private fun isGpsProviderEnabled(): Boolean =
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
}
