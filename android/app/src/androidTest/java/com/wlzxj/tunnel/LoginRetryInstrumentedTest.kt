package com.wlzxj.tunnel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.data.AppPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginRetryInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val mockServer = MockWebServer()
    private var meCalls = 0

    @Before
    fun setUp() {
        meCalls = 0
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    path.endsWith("/login") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"session":"sess-mock","expires_at":4102444800}""")
                    path.endsWith("/me") -> {
                        meCalls++
                        if (meCalls == 1) {
                            MockResponse()
                                .setResponseCode(401)
                                .setBody("""{"error":"会话无效"}""")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(
                                    """{
                                    "username":"retryuser",
                                    "token_prefix":"ab",
                                    "domain_limit":3,
                                    "is_admin":false,
                                    "audit_retention_days":90
                                }""",
                                )
                        }
                    }
                    path.endsWith("/config") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"registration":true,"domain_limit":3}""")
                    path.endsWith("/audit") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"events":[],"retention_days":90}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()
        val origin = "http://127.0.0.1:${mockServer.port}"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            AppPreferences(ctx).updateConnection {
                it.copy(server = origin, debugAllowLocalHttp = true)
            }
        }
    }

    @After
    fun tearDown() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("tunnel_origin") ?: "http://127.0.0.1:19092"
        val target = args.getString("tunnel_target") ?: "http://127.0.0.1:19093"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentedFixturePrefs.restore(ctx, InstrumentedFixturePrefs.baseline(origin, target))
        mockServer.shutdown()
    }

    @Test
    fun loginMeFailureThenRetrySucceedsAndClearsLoading() {
        composeRule.onNodeWithTag("nav_account").performClick()
        composeRule.onNodeWithTag("account_username").performTextReplacement("retryuser")
        composeRule.onNodeWithTag("account_password").performTextReplacement("secret")
        composeRule.onNodeWithText("登录").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("会话无效", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("登录").assertExists()
        composeRule.onNodeWithText("登录").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("退出登录", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(meCalls >= 2)
    }
}
