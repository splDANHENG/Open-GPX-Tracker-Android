// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

data class RecordingControlLockState(
    val trackId: Long? = null,
    val isVisible: Boolean = false,
    val isLocked: Boolean = false,
    val tapCount: Int = 0,
)

/** A triple tap unlocks controls until there have been 15 seconds without interaction. */
internal class RecordingControlLock {
    var state = RecordingControlLockState()
        private set

    private var unlockedForTrack = false
    private var unlockedAtMillis: Long? = null
    private var lastTapAtMillis: Long? = null

    fun reset() {
        state = RecordingControlLockState()
        unlockedForTrack = false
        unlockedAtMillis = null
        lastTapAtMillis = null
    }

    fun beginTrack(id: Long) {
        if (state.trackId == id) return
        state = RecordingControlLockState(trackId = id)
        unlockedForTrack = false
        unlockedAtMillis = null
        lastTapAtMillis = null
    }

    fun observeTrackAge(id: Long, ageMillis: Long) {
        beginTrack(id)
        if (ageMillis >= LOCK_AFTER_MILLIS && !state.isVisible) {
            state = state.copy(isVisible = true, isLocked = !unlockedForTrack)
        }
    }

    fun tap(id: Long, nowMillis: Long) {
        expireUnlock(id, nowMillis)
        if (state.trackId != id || !state.isLocked) return
        val previousTap = lastTapAtMillis
        val count = if (previousTap != null && nowMillis - previousTap in 0..TAP_WINDOW_MILLIS) {
            state.tapCount + 1
        } else {
            1
        }
        lastTapAtMillis = nowMillis
        if (count >= REQUIRED_TAPS) {
            unlockedForTrack = true
            unlockedAtMillis = nowMillis
            lastTapAtMillis = null
            state = state.copy(isLocked = false, tapCount = 0)
        } else {
            state = state.copy(tapCount = count)
        }
    }

    fun expireUnlock(id: Long, nowMillis: Long): Boolean {
        val unlockedAt = unlockedAtMillis ?: return false
        if (state.trackId != id || nowMillis - unlockedAt < UNLOCK_DURATION_MILLIS) return false
        unlockedForTrack = false
        unlockedAtMillis = null
        lastTapAtMillis = null
        state = state.copy(isLocked = true, tapCount = 0)
        return true
    }

    fun noteInteraction(id: Long, nowMillis: Long): Boolean {
        expireUnlock(id, nowMillis)
        if (state.trackId != id || state.isLocked || unlockedAtMillis == null) return false
        unlockedAtMillis = nowMillis
        return true
    }

    companion object {
        const val LOCK_AFTER_MILLIS = 60_000L
        const val UNLOCK_DURATION_MILLIS = 15_000L
        private const val TAP_WINDOW_MILLIS = 1_500L
        private const val REQUIRED_TAPS = 3
    }
}
