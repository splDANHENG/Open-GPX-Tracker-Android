// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingControlLockTest {
    @Test
    fun interactionsExtendUnlockButNeverUnlockExpiredControls() {
        val lock = RecordingControlLock()
        lock.observeTrackAge(7L, 60_000L)
        lock.tap(7L, 1_000L)
        lock.tap(7L, 1_100L)
        lock.tap(7L, 1_200L)
        assertFalse(lock.noteInteraction(8L, 10_000L))
        assertTrue(lock.noteInteraction(7L, 10_000L))
        assertFalse(lock.expireUnlock(7L, 16_200L))
        assertTrue(lock.expireUnlock(7L, 25_000L))
        assertFalse(lock.noteInteraction(7L, 25_001L))
        assertTrue(lock.state.isLocked)
    }

    @Test
    fun locksAtOneMinuteAndRelocksFifteenSecondsAfterThreeQuickTaps() {
        val lock = RecordingControlLock()
        lock.observeTrackAge(7L, 59_999L)
        assertFalse(lock.state.isVisible)

        lock.observeTrackAge(7L, 60_000L)
        assertTrue(lock.state.isLocked)
        lock.tap(7L, 1_000L)
        lock.tap(7L, 1_400L)
        assertEquals(2, lock.state.tapCount)
        lock.tap(7L, 1_800L)
        assertFalse(lock.state.isLocked)

        assertFalse(lock.expireUnlock(7L, 16_799L))
        assertFalse(lock.state.isLocked)
        assertTrue(lock.expireUnlock(7L, 16_800L))
        assertTrue(lock.state.isLocked)
        assertTrue(lock.state.isVisible)

        lock.tap(7L, 17_000L)
        lock.tap(7L, 17_400L)
        lock.tap(7L, 17_800L)
        assertFalse(lock.state.isLocked)
        assertFalse(lock.expireUnlock(7L, 17_800L))
        assertTrue(lock.expireUnlock(7L, 32_800L))
        assertTrue(lock.state.isLocked)
    }

    @Test
    fun slowTapsRestartTheSequenceAndNewTrackLocksAgain() {
        val lock = RecordingControlLock()
        lock.observeTrackAge(7L, 60_000L)
        lock.tap(7L, 1_000L)
        lock.tap(7L, 2_501L)
        assertEquals(1, lock.state.tapCount)
        lock.tap(8L, 2_600L)
        assertEquals(1, lock.state.tapCount)
        lock.tap(7L, 2_700L)
        lock.tap(7L, 2_800L)
        assertFalse(lock.state.isLocked)

        lock.observeTrackAge(8L, 60_000L)
        assertTrue(lock.state.isLocked)
        assertEquals(0, lock.state.tapCount)
    }

    @Test
    fun aTapAfterTheUnlockWindowStartsANewUnlockSequence() {
        val lock = RecordingControlLock()
        lock.observeTrackAge(7L, 60_000L)
        lock.tap(7L, 1_000L)
        lock.tap(7L, 1_100L)
        lock.tap(7L, 1_200L)

        lock.tap(7L, 16_200L)

        assertTrue(lock.state.isLocked)
        assertEquals(1, lock.state.tapCount)
    }
}
