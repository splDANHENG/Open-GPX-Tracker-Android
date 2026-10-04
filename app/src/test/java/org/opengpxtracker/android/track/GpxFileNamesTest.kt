// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpxFileNamesTest {
    @Test
    fun placePrefixIsAddedBeforeTheGpxName() {
        assertEquals("bristol-track.gpx", GpxFileNames.withPlacePrefix(" bristol ", "track"))
        assertEquals("bristol-track.gpx", GpxFileNames.withPlacePrefix("bristol", "track.GPX"))
        assertEquals("track.gpx", GpxFileNames.withPlacePrefix(" ", "track"))
    }

    @Test
    fun invalidOrOversizedPlacePrefixCannotBeExported() {
        assertNull(GpxFileNames.withPlacePrefix("bristol/west", "track"))
        assertNull(GpxFileNames.withPlacePrefix("b".repeat(120), "track"))
    }
}
