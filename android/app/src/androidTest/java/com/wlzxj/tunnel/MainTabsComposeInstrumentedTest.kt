package com.wlzxj.tunnel

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainTabsComposeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigatesThreeTabs() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始连接").assertExists()
        composeRule.onNodeWithText("域名").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("登录账号后可查看已保留的子域名与名额。", substring = true).assertExists()
        composeRule.onNodeWithText("账号").performClick()
        composeRule.onNodeWithText("登录").assertExists()
        composeRule.onNodeWithText("连接").performClick()
        composeRule.onNodeWithText("目标地址").assertExists()
    }

    @Test
    fun connectValidationShowsError() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connect_mode_token").performClick()
        composeRule.onNodeWithTag("connect_mode_token").assertIsSelected()
        composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("连接 Token 无效", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun connectScreenOffersLoginNavigationWhenLoggedOut() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("去登录").assertExists()
        composeRule.onNodeWithText("使用当前账号").assertDoesNotExist()
    }
}
