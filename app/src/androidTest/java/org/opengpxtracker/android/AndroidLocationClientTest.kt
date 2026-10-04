// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opengpxtracker.android.location.AndroidLocationClient
import org.opengpxtracker.android.location.LocationEvent

/** Run only on a dedicated test device: this temporarily replaces a system provider. */
class AndroidLocationClientTest {
    @Suppress("DEPRECATION")
    @Test
    fun systemProviderDeliversCoordinatesAltitudeAndSpeed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = if (Build.VERSION.SDK_INT >= 31 &&
            LocationManager.FUSED_PROVIDER in manager.allProviders
        ) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("appops set ${context.packageName} android:mock_location allow")
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            manager.addTestProvider(provider, false, true, false, false, true, true, true, 1, 1)
            manager.setTestProviderEnabled(provider, true)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000) {
                    AndroidLocationClient(context).updates()
                        .filterIsInstance<LocationEvent.Sample>()
                        .first { it.location.latitude == 51.45 && it.location.longitude == -2.58 }
                        .location
                }
            }
            repeat(5) {
                delay(500)
                manager.setTestProviderLocation(provider, Location(provider).apply {
                    latitude = 51.45
                    longitude = -2.58
                    altitude = 42.0
                    if (Build.VERSION.SDK_INT >= 34) mslAltitudeMeters = 25.0
                    speed = 4.0f
                    accuracy = 5.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
            val sample = result.await()
            assertEquals(if (Build.VERSION.SDK_INT >= 34) 25.0 else 42.0,
                sample.altitudeMeters!!, 0.001)
            assertEquals(4.0f, sample.speedMetersPerSecond!!, 0.001f)
            assertEquals(5.0f, sample.accuracyMeters, 0.001f)
        } finally {
            runCatching { manager.removeTestProvider(provider) }
            shell("appops set ${context.packageName} android:mock_location default")
            activity.close()
        }
    }
}
