package com.wlzxj.tunnel

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.data.TunnelApi
import com.wlzxj.tunnel.jni.TunnelNative
import com.wlzxj.tunnel.service.TunnelForegroundService
import com.wlzxj.tunnel.service.TunnelServiceState
import com.wlzxj.tunnel.service.TunnelUiPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TunnelFixtureInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val origin = args.getString("tunnel_origin") ?: "http://127.0.0.1:19092"
    private val target = args.getString("tunnel_target") ?: "http://127.0.0.1:19093"
    private val debugLocal = true

    @Test
    fun accountApiRoundTrip() = runBlocking {
        val api = TunnelApi()
        val cfg = api.fetchConfig(origin, debugLocal)
        assertTrue(cfg.registrationEnabled || cfg.domainLimit >= 0)
        val user = "and${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val pass = "TestPass-${UUID.randomUUID()}"
        api.register(origin, debugLocal, user, pass)
        val (session, _) = api.login(origin, debugLocal, user, pass)
        val (token, _) = api.createToken(origin, debugLocal, session)
        assertTrue(token.length >= 32)
        val (domains, limit) = api.domains(origin, debugLocal, session)
        assertTrue(limit >= 0)
        assertTrue(domains.isNotEmpty() || domains.isEmpty())
    }

    @Test
    fun tunnelForwardsHelloAndStops() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val api = TunnelApi()
        val user = "tun${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val pass = "TestPass-${UUID.randomUUID()}"
        api.register(origin, debugLocal, user, pass)
        val (session, _) = api.login(origin, debugLocal, user, pass)
        val (token, _) = api.createToken(origin, debugLocal, session)

        val start = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_SERVER, origin)
            putExtra(TunnelForegroundService.EXTRA_TARGET, target)
            putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, "127.0.0.1:19093")
            putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, "token")
            putExtra(TunnelForegroundService.EXTRA_AUTH_VALUE, token)
            putExtra(TunnelForegroundService.EXTRA_USERNAME, "")
            putExtra(TunnelForegroundService.EXTRA_ALLOW_INSECURE, true)
        }
        ContextCompat.startForegroundService(ctx, start)

        val publicUrl = withTimeout(90_000) {
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
        assertTrue(publicUrl.isNotBlank())

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val publicUri = java.net.URI(publicUrl)
        val tunnelHost = publicUri.host ?: error("missing host in $publicUrl")
        val originUri = java.net.URI(origin)
        val viaOrigin = "${originUri.scheme}://${originUri.host}:${originUri.port}/hello.txt"
        val request = Request.Builder()
            .url(viaOrigin)
            .header("Host", tunnelHost)
            .build()
        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            val body = response.body?.string() ?: ""
            assertTrue(body.contains("Android JNI forwarding verified"))
        }

        val stop = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP
        }
        ctx.startService(stop)

        withTimeout(30_000) {
            while (TunnelServiceState.state.value.phase == TunnelUiPhase.Stopping ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Ready ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Connecting
            ) {
                delay(200)
            }
        }
        assertFalse(TunnelNative.nativeIsRunning())
    }

    @Test
    fun passwordAuthNamedSubdomainOnlineThenOffline() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val api = TunnelApi()
        val user = "pwd${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val pass = "TestPass-${UUID.randomUUID()}"
        api.register(origin, debugLocal, user, pass)
        val (session, _) = api.login(origin, debugLocal, user, pass)
        val subdomain = "t${UUID.randomUUID().toString().replace("-", "").take(6).lowercase()}"

        val start = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_SERVER, origin)
            putExtra(TunnelForegroundService.EXTRA_TARGET, target)
            putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, "127.0.0.1:19093")
            putExtra(TunnelForegroundService.EXTRA_NAME, subdomain)
            putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, "password")
            putExtra(TunnelForegroundService.EXTRA_AUTH_VALUE, pass)
            putExtra(TunnelForegroundService.EXTRA_USERNAME, user)
            putExtra(TunnelForegroundService.EXTRA_ALLOW_INSECURE, true)
        }
        ContextCompat.startForegroundService(ctx, start)

        val publicUrl = withTimeout(90_000) {
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
        assertTrue(publicUrl.isNotBlank())

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val publicUri = java.net.URI(publicUrl)
        val tunnelHost = publicUri.host ?: error("missing host in $publicUrl")
        val originUri = java.net.URI(origin)
        val viaOrigin = "${originUri.scheme}://${originUri.host}:${originUri.port}/hello.txt"
        client.newCall(
            Request.Builder()
                .url(viaOrigin)
                .header("Host", tunnelHost)
                .build(),
        ).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertTrue((response.body?.string() ?: "").contains("Android JNI forwarding verified"))
        }

        val onlineRow = withTimeout(30_000) {
            while (true) {
                val (rows, _) = api.domains(origin, debugLocal, session)
                val row = rows.find { it.name == subdomain }
                if (row?.status == "online") return@withTimeout row
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            error("domain not online")
        }
        assertEquals("online", onlineRow.status)

        val stop = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP
        }
        ctx.startService(stop)
        withTimeout(30_000) {
            while (TunnelServiceState.state.value.phase == TunnelUiPhase.Stopping ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Ready ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Connecting
            ) {
                delay(200)
            }
        }

        withTimeout(45_000) {
            while (true) {
                val (rows, _) = api.domains(origin, debugLocal, session)
                val row = rows.find { it.name == subdomain }
                if (row?.status == "offline") return@withTimeout
                delay(500)
            }
        }
        val (finalRows, _) = api.domains(origin, debugLocal, session)
        assertEquals("offline", finalRows.find { it.name == subdomain }?.status)
    }

    @Test
    fun invalidTokenEventuallyClearsRunning() = runBlocking {
        val callback = object : com.wlzxj.tunnel.jni.TunnelEventCallback {
            override fun onEvent(json: String) = Unit
        }
        val gen = TunnelNative.nativeStart(
            origin,
            target,
            null,
            "token",
            "short",
            "",
            true,
            callback,
        )
        assertTrue(gen > 0)
        withTimeout(30_000) {
            while (TunnelNative.nativeIsRunning()) {
                delay(200)
            }
        }
        assertFalse(TunnelNative.nativeIsRunning())
    }
}
