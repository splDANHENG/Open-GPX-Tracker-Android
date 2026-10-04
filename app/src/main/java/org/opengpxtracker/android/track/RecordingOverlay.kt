// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.opengpxtracker.android.MainActivity
import org.opengpxtracker.android.location.LocationSample
import org.opengpxtracker.android.location.LOCATION_STALE_AFTER_MILLIS
import java.util.Locale
import kotlin.math.roundToInt

/** An optional, non-focusable window owned by the recording foreground service. */
internal class RecordingOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("recording_overlay", Context.MODE_PRIVATE)
    private var root: View? = null
    private var position: TextView? = null
    private var altitude: TextView? = null
    private var speed: TextView? = null
    private var distance: TextView? = null
    private var duration: TextView? = null

    fun update(recording: TrackRecording, sample: LocationSample?) {
        if (!Settings.canDrawOverlays(context)) {
            remove()
            return
        }
        if (root == null) add()
        val fresh = sample != null &&
            (SystemClock.elapsedRealtimeNanos() - sample.elapsedRealtimeNanos) in 0L..FRESH_NANOS
        position?.text = if (fresh) {
            String.format(Locale.US, "经纬度  %.5f, %.5f", sample.latitude, sample.longitude)
        } else "经纬度  --"
        altitude?.text = if (fresh && sample?.altitudeMeters?.isFinite() == true) {
            String.format(Locale.US, "海拔  %.0f m", sample.altitudeMeters)
        } else "海拔  --"
        speed?.text = if (fresh && sample?.speedMetersPerSecond?.let { it.isFinite() && it >= 0f } == true) {
            String.format(Locale.US, "当前车速  %.1f km/h", sample.speedMetersPerSecond!! * 3.6f)
        } else "当前车速  --"
        distance?.text = if (recording.distanceMeters < 1_000.0) {
            String.format(Locale.US, "距离  %.0f m", recording.distanceMeters)
        } else {
            String.format(Locale.US, "距离  %.2f km", recording.distanceMeters / 1_000.0)
        }
        val seconds = recording.elapsedAt(SystemClock.elapsedRealtime()) / 1_000L
        duration?.text = String.format(
            Locale.US,
            "目前时长  %02d:%02d:%02d",
            seconds / 3_600L,
            seconds / 60L % 60L,
            seconds % 60L,
        )
    }

    fun remove() {
        val detached = root
        root = null
        position = null
        altitude = null
        speed = null
        distance = null
        duration = null
        detached?.let { runCatching { windowManager.removeView(it) } }
    }

    private fun add() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun line(text: String, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = if (bold) 15f else 13f
            setTextColor(Color.WHITE)
            if (bold) setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, dp(if (bold) 0 else 5), 0, 0)
        }
        val panel = RecordingPanel(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 25, 36, 52))
                cornerRadius = dp(14).toFloat()
            }
            elevation = dp(8).toFloat()
            contentDescription = "GPS正在录制中，点击返回应用"
            setOnClickListener {
                runCatching {
                    context.startActivity(Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            }
        }
        panel.addView(line("GPS正在录制中", bold = true))
        position = line("经纬度  --").also(panel::addView)
        altitude = line("海拔  --").also(panel::addView)
        speed = line("当前车速  --").also(panel::addView)
        distance = line("距离  0 m").also(panel::addView)
        duration = line("目前时长  00:00:00").also(panel::addView)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = preferences.getInt(PREF_Y, dp(72))
        }
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        panel.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) dragging = true
                    if (dragging) {
                        val screen = context.resources.displayMetrics
                        params.x = (downX + dx.roundToInt())
                            .coerceIn(0, (screen.widthPixels - panel.width).coerceAtLeast(0))
                        params.y = (downY + dy.roundToInt())
                            .coerceIn(0, (screen.heightPixels - panel.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(panel, params) }.onFailure { remove() }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        preferences.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        windowManager.addView(panel, params)
        root = panel
        panel.post {
            if (root === panel) {
                val screen = context.resources.displayMetrics
                params.x = preferences.getInt(PREF_X, screen.widthPixels - panel.width - dp(12))
                    .coerceIn(0, (screen.widthPixels - panel.width).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (screen.heightPixels - panel.height).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(panel, params) }.onFailure { remove() }
            }
        }
    }

    private companion object {
        const val FRESH_NANOS = LOCATION_STALE_AFTER_MILLIS * 1_000_000L
        const val PREF_X = "x"
        const val PREF_Y = "y"
    }

    private class RecordingPanel(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean = super.performClick()
    }
}
