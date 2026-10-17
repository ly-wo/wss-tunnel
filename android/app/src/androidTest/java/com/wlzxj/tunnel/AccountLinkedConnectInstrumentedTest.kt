package com.wlzxj.tunnel

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.wlzxj.tunnel.data.TunnelApi
import com.wlzxj.tunnel.service.TunnelForegroundService
import com.wlzxj.tunnel.service.TunnelServiceOwnership
import com.wlzxj.tunnel.service.TunnelServiceState
import com.wlzxj.tunnel.service.TunnelUiPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AccountLinkedConnectInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val origin = args.getString("tunnel_origin") ?: "http://127.0.0.1:19092"
    private val target = args.getString("tunnel_target") ?: "http://127.0.0.1:19093"
    private val debugLocal = true
    private val fixtureBaseline = InstrumentedFixturePrefs.baseline(origin, target)

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentedFixturePrefs.restore(ctx, fixtureBaseline)
    }

    @After
    fun tearDown() {
        runBlocking {
            stopTunnelAndWaitIdle()
            logoutIfLoggedIn()
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentedFixturePrefs.restore(ctx, fixtureBaseline)
            delay(300)
        }
    }

    @Test
    fun loginThenCurrentAccountConnectWithoutRotatingToken() {
        runBlocking {
            val api = TunnelApi()
            val user = "ui${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)
            val (seedSession, _) = api.login(origin, debugLocal, user, pass)
            val (_, prefixBefore) = api.createToken(origin, debugLocal, seedSession)
            api.logout(origin, debugLocal, seedSession)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            loginThroughAccountTab(user, pass)

            composeRule.onNodeWithTag("nav_connect").performClick()
            composeRule.onNodeWithTag("connect_mode_account").assertIsSelected()
            assertTrue(composeRule.onAllNodesWithTag("connect_token").fetchSemanticsNodes().isEmpty())
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()

            val publicUrl = waitForTunnelReady()
            assertHelloViaTunnelWithRetry(publicUrl)

            val (session, _) = api.login(origin, debugLocal, user, pass)
            val profile = api.me(origin, debugLocal, session)
            assertEquals(prefixBefore, profile.tokenPrefix)
            val (domains, _) = api.domains(origin, debugLocal, session)
            assertTrue(domains.any { it.status == "online" })

            composeRule.onNodeWithTag("nav_account").performClick()
            composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
            waitForTunnelIdle()
        }
    }

    @Test
    fun manualTokenTunnelSurvivesAccountLogout() {
        runBlocking {
            val api = TunnelApi()
            val user = "mt${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)
            val (session, _) = api.login(origin, debugLocal, user, pass)
            val (token, _) = api.createToken(origin, debugLocal, session)
            api.logout(origin, debugLocal, session)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            composeRule.onNodeWithTag("connect_mode_token").performClick()
            composeRule.onNodeWithTag("connect_mode_token").assertIsSelected()
            composeRule.onNodeWithTag("connect_token").performTextReplacement(token)
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
            waitForTunnelReady()

            loginThroughAccountTab(user, pass)
            composeRule.onNodeWithTag("nav_account").performClick()
            composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
            delay(800)
            assertEquals(TunnelUiPhase.Ready, TunnelServiceState.state.value.phase)
        }
    }

    @Test
    fun accountConnectShowsValidationErrorForBadTarget() {
        runBlocking {
            val api = TunnelApi()
            val user = "bad${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            loginThroughAccountTab(user, pass)
            composeRule.onNodeWithTag("nav_connect").performClick()
            composeRule.onNodeWithTag("connect_mode_account").assertIsSelected()
            assertTrue(composeRule.onAllNodesWithTag("connect_token").fetchSemanticsNodes().isEmpty())
            val badTarget = "not-a-valid-target"
            composeRule.onNodeWithTag("connect_target").performScrollTo()
                .performTextReplacement(badTarget)
            composeRule.waitUntil(10_000) {
                composeRule.onNodeWithTag("connect_target").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.EditableText)?.text == badTarget
            }
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText("目标须为 http(s):// 或 ws(s)://", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("connect_error")
                .assertIsDisplayed()

            composeRule.onNodeWithTag("connect_target").performScrollTo()
                .performTextReplacement("")
            composeRule.waitUntil(10_000) {
                composeRule.onNodeWithTag("connect_target").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
            }
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText("目标须为 http(s):// 或 ws(s)://", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun authModeSwitchBetweenAccountAndManualToken() {
        runBlocking {
            val api = TunnelApi()
            val user = "sw${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            loginThroughAccountTab(user, pass)
            composeRule.onNodeWithTag("nav_connect").performClick()
            composeRule.onNodeWithTag("connect_mode_account").assertIsSelected()
            composeRule.onNodeWithTag("connect_mode_token").performClick()
            composeRule.onNodeWithTag("connect_mode_token").assertIsSelected()
            composeRule.onNodeWithTag("connect_mode_account").assertIsNotSelected()
            composeRule.onNodeWithTag("connect_token").assertIsDisplayed()
            composeRule.onNodeWithTag("connect_mode_account").performClick()
            composeRule.onNodeWithTag("connect_mode_account").assertIsSelected()
            assertTrue(composeRule.onAllNodesWithTag("connect_token").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun staleAccountStopDoesNotKillCurrentConnection() {
        runBlocking {
            val api = TunnelApi()
            val user = "so${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            loginThroughAccountTab(user, pass)
            composeRule.onNodeWithTag("nav_connect").performClick()
            composeRule.onNodeWithTag("connect_mode_account").assertIsSelected()
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
            waitForTunnelReady()
            val activeConnId = TunnelServiceState.state.value.connectionId
            assertTrue(activeConnId > 0L)

            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startService(
                Intent(ctx, TunnelForegroundService::class.java).apply {
                    action = TunnelForegroundService.ACTION_STOP_ACCOUNT
                    putExtra(TunnelServiceOwnership.EXTRA_OWNERSHIP_SERVER, origin)
                    putExtra(TunnelServiceOwnership.EXTRA_OWNERSHIP_USER, user)
                    putExtra(TunnelServiceOwnership.EXTRA_CONNECTION_ID, activeConnId - 1)
                },
            )
            delay(1500)
            assertEquals(TunnelUiPhase.Ready, TunnelServiceState.state.value.phase)
            assertEquals(activeConnId, TunnelServiceState.state.value.connectionId)
        }
    }

    @Test
    fun staleStartIntentIgnoredWhileTunnelReady() {
        runBlocking {
            val api = TunnelApi()
            val user = "st${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val pass = "TestPass-${UUID.randomUUID()}"
            api.register(origin, debugLocal, user, pass)
            val (session, _) = api.login(origin, debugLocal, user, pass)
            val (token, _) = api.createToken(origin, debugLocal, session)
            api.logout(origin, debugLocal, session)

            stopTunnelAndWaitIdle()
            configureFixtureConnection()
            composeRule.onNodeWithTag("connect_mode_token").performClick()
            composeRule.onNodeWithTag("connect_mode_token").assertIsSelected()
            composeRule.onNodeWithTag("connect_token").performTextReplacement(token)
            composeRule.onNodeWithText("开始连接").performScrollTo().performClick()
            waitForTunnelReady()
            val readyUrl = TunnelServiceState.state.value.publicUrl

            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val stale = Intent(ctx, TunnelForegroundService::class.java).apply {
                action = TunnelForegroundService.ACTION_START
                putExtra(TunnelForegroundService.EXTRA_SERVER, origin)
                putExtra(TunnelForegroundService.EXTRA_TARGET, target)
                putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, "stale")
                putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, "token")
                putExtra(TunnelForegroundService.EXTRA_AUTH_VALUE, token)
                putExtra(TunnelServiceOwnership.EXTRA_START_SERVER_EPOCH, 0)
                putExtra(TunnelServiceOwnership.EXTRA_START_ACCOUNT_EPOCH, 0)
                putExtra(TunnelServiceOwnership.EXTRA_START_GUARD_GENERATION, 0L)
                TunnelServiceOwnership.writeToIntent(this, TunnelServiceOwnership.Manual)
            }
            ContextCompat.startForegroundService(ctx, stale)
            delay(1500)
            assertEquals(TunnelUiPhase.Ready, TunnelServiceState.state.value.phase)
            assertEquals(readyUrl, TunnelServiceState.state.value.publicUrl)
        }
    }

    private suspend fun configureFixtureConnection() {
        stopTunnelAndWaitIdle()
        composeRule.onNodeWithTag("nav_connect").performClick()
        composeRule.waitForIdle()
        ensureDebugHttpEnabled()
        delay(200)
    }

    private fun ensureDebugHttpEnabled() {
        val node = composeRule.onNodeWithTag("connect_debug_http")
        val state = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        if (state != ToggleableState.On) {
            node.performClick()
        }
    }

    private suspend fun stopTunnelAndWaitIdle() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startService(
            Intent(ctx, TunnelForegroundService::class.java).apply {
                action = TunnelForegroundService.ACTION_STOP
            },
        )
        withTimeout(30_000) {
            while (TunnelServiceState.state.value.phase == TunnelUiPhase.Connecting ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Ready ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Stopping
            ) {
                delay(200)
            }
        }
    }

    private fun logoutIfLoggedIn() {
        composeRule.waitForIdle()
        if (runCatching {
                composeRule.onNodeWithText("退出登录", substring = true).assertIsDisplayed()
                true
            }.getOrDefault(false)
        ) {
            composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
            composeRule.waitForIdle()
            return
        }
        composeRule.onNodeWithTag("nav_account").performClick()
        if (runCatching {
                composeRule.onNodeWithText("退出登录", substring = true).assertIsDisplayed()
                true
            }.getOrDefault(false)
        ) {
            composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
    }

    private fun loginThroughAccountTab(user: String, pass: String) {
        composeRule.onNodeWithTag("nav_account").performClick()
        composeRule.onNodeWithTag("account_username").performTextReplacement(user)
        composeRule.onNodeWithTag("account_password").performTextReplacement(pass)
        composeRule.onNodeWithText("登录").performClick()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText("退出登录", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun waitForTunnelReady(): String = withTimeout(90_000) {
        while (true) {
            val state = TunnelServiceState.state.value
            if (state.phase == TunnelUiPhase.Failed) {
                error(state.message ?: "tunnel failed")
            }
            if (state.phase == TunnelUiPhase.Ready && !state.publicUrl.isNullOrBlank()) {
                return@withTimeout state.publicUrl!!
            }
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    private suspend fun waitForTunnelIdle() = withTimeout(45_000) {
        while (TunnelServiceState.state.value.phase == TunnelUiPhase.Connecting ||
            TunnelServiceState.state.value.phase == TunnelUiPhase.Ready ||
            TunnelServiceState.state.value.phase == TunnelUiPhase.Stopping
        ) {
            delay(200)
        }
    }

    private suspend fun assertHelloViaTunnelWithRetry(publicUrl: String) {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val publicUri = java.net.URI(publicUrl)
        val tunnelHost = publicUri.host ?: error("missing host")
        val originUri = java.net.URI(origin)
        val viaOrigin = "${originUri.scheme}://${originUri.host}:${originUri.port}/hello.txt"
        withTimeout(30_000) {
            while (true) {
                val ok = client.newCall(
                    Request.Builder()
                        .url(viaOrigin)
                        .header("Host", tunnelHost)
                        .build(),
                ).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    response.isSuccessful && body.contains("Android JNI forwarding verified")
                }
                if (ok) return@withTimeout Unit
                delay(500)
            }
        }
    }
}
