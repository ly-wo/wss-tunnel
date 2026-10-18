package com.wlzxj.tunnel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun updateCardVisibleWhenLoggedOut() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("账号").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("app_update_card").assertExists()
        composeRule.onNodeWithTag("app_update_check").assertExists()
        composeRule.onNodeWithText("当前版本：", substring = true).assertExists()
    }
}
