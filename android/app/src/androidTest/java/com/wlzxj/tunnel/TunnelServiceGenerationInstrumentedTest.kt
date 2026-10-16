package com.wlzxj.tunnel

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.data.TunnelApi
import com.wlzxj.tunnel.service.TunnelForegroundService
import com.wlzxj.tunnel.service.TunnelServiceState
import com.wlzxj.tunnel.service.TunnelUiPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TunnelServiceGenerationInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staleGenerationEventIsIgnored() = runBlocking {
        val service = TunnelForegroundService()
        val field = TunnelForegroundService::class.java.getDeclaredField("activeGeneration").apply {
            isAccessible = true
        }
        field.setLong(service, 2L)
        TunnelServiceState.update(
            com.wlzxj.tunnel.service.TunnelRuntimeState(
                phase = TunnelUiPhase.Connecting,
                generation = 2L,
            ),
        )
        val stale = JSONObject()
            .put("generation", 1L)
            .put("phase", "ready")
            .put("public_url", "http://stale.example")
        val handle = TunnelForegroundService::class.java.getDeclaredMethod("handleEvent", String::class.java)
            .apply { isAccessible = true }
        handle.invoke(service, stale.toString())
        assertNotEquals(TunnelUiPhase.Ready, TunnelServiceState.state.value.phase)
    }

    @Test
    fun stopWhileConnectingReachesIdle() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("tunnel_origin") ?: "http://127.0.0.1:19092"
        val target = args.getString("tunnel_target") ?: "http://127.0.0.1:19093"
        val api = TunnelApi()
        val user = "stp${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val pass = "TestPass-${UUID.randomUUID()}"
        api.register(origin, true, user, pass)
        val (session, _) = api.login(origin, true, user, pass)
        val (token, _) = api.createToken(origin, true, session)

        val start = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_SERVER, origin)
            putExtra(TunnelForegroundService.EXTRA_TARGET, target)
            putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, "stop-test")
            putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, "token")
            putExtra(TunnelForegroundService.EXTRA_AUTH_VALUE, token)
            putExtra(TunnelForegroundService.EXTRA_ALLOW_INSECURE, true)
        }
        ContextCompat.startForegroundService(ctx, start)
        delay(300)
        val stop = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP
        }
        ctx.startService(stop)
        withTimeout(45_000) {
            while (TunnelServiceState.state.value.phase == TunnelUiPhase.Connecting ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Stopping ||
                TunnelServiceState.state.value.phase == TunnelUiPhase.Ready
            ) {
                delay(200)
            }
        }
        assertEquals(TunnelUiPhase.Idle, TunnelServiceState.state.value.phase)
    }

    @Test
    fun failedPhaseSurvivesUntilStopped() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("tunnel_origin") ?: "http://127.0.0.1:19092"
        val target = args.getString("tunnel_target") ?: "http://127.0.0.1:19093"
        val start = Intent(ctx, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_SERVER, origin)
            putExtra(TunnelForegroundService.EXTRA_TARGET, target)
            putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, "fail-test")
            putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, "token")
            putExtra(
                TunnelForegroundService.EXTRA_AUTH_VALUE,
                "0000000000000000000000000000000000000000000000000000000000000000",
            )
            putExtra(TunnelForegroundService.EXTRA_ALLOW_INSECURE, true)
        }
        ContextCompat.startForegroundService(ctx, start)
        withTimeout(45_000) {
            while (TunnelServiceState.state.value.phase != TunnelUiPhase.Failed) {
                delay(200)
            }
        }
        assertTrue(TunnelServiceState.state.value.message?.isNotBlank() == true)
    }
}
