// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openStreetMapAttributionIsVisible() {
        if (composeRule.onAllNodesWithText("稍后").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("稍后").performClick()
        }
        composeRule
            .onNodeWithText("© OpenStreetMap contributors")
            .assertIsDisplayed()
    }

    @Test
    fun mapSourceAndGpxFileControlsAreVisibleAndClickable() {
        if (composeRule.onAllNodesWithText("稍后").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("稍后").performClick()
        }
        composeRule.onNodeWithText("街道图").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("地形图").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("GPX 文件").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("导入 GPX").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()
    }
}
