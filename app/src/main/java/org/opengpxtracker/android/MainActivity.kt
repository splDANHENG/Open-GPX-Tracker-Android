// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.opengpxtracker.android.ui.MapScreen
import org.opengpxtracker.android.ui.OpenGpxTrackerTheme
import org.opengpxtracker.android.track.RecordingSession

class MainActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            RecordingSession.noteInteraction()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Keep tiles the user actually viewed available when the network is absent.
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(250L * 1024L * 1024L, null)
        enableEdgeToEdge()

        setContent {
            OpenGpxTrackerTheme {
                MapScreen()
            }
        }
    }
}
