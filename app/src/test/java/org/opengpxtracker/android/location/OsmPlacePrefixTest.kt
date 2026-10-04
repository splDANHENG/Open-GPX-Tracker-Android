// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsmPlacePrefixTest {
    @Test
    fun cityLevelNameWinsOverSmallerPlacesAndCounties() {
        assertEquals(
            "Bristol",
            OsmPlacePrefix.selectCityLevelName(
                mapOf("suburb" to "Clifton", "city" to "Bristol", "county" to "Somerset"),
            ),
        )
        assertEquals(
            "Bath",
            OsmPlacePrefix.selectCityLevelName(mapOf("town" to "Bath", "village" to "Nearby")),
        )
    }

    @Test
    fun usesMunicipalityOrVillageButNeverCountyOrUnsafeName() {
        assertEquals("Ullensaker", OsmPlacePrefix.selectCityLevelName(mapOf("municipality" to "Ullensaker")))
        assertEquals("Mogreina", OsmPlacePrefix.selectCityLevelName(mapOf("village" to "Mogreina")))
        assertNull(OsmPlacePrefix.selectCityLevelName(mapOf("county" to "Avon")))
        assertNull(OsmPlacePrefix.selectCityLevelName(mapOf("city" to "Bad/Name")))
    }
}
