package com.wlzxj.tunnel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("开始连接").performClick()
        composeRule.onNodeWithText("连接 Token 无效", substring = true).assertExists()
    }
}
