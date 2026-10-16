package com.wlzxj.tunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.wlzxj.tunnel.MainActivity
import com.wlzxj.tunnel.R
import com.wlzxj.tunnel.jni.TunnelEventCallback
import com.wlzxj.tunnel.jni.TunnelNative
import org.json.JSONObject

enum class TunnelUiPhase {
    Idle,
    Connecting,
    Ready,
    Stopping,
    Failed,
}

data class TunnelRuntimeState(
    val phase: TunnelUiPhase = TunnelUiPhase.Idle,
    val generation: Long = 0L,
    val publicUrl: String? = null,
    val targetDisplay: String? = null,
    val message: String? = null,
    val startedAtMillis: Long? = null,
)

object TunnelServiceState {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(TunnelRuntimeState())
    val state: kotlinx.coroutines.flow.StateFlow<TunnelRuntimeState> = _state

    internal fun update(state: TunnelRuntimeState) {
        _state.value = state
    }
}

class TunnelForegroundService : Service(), TunnelEventCallback {
    companion object {
        const val ACTION_START = "com.wlzxj.tunnel.START"
        const val ACTION_STOP = "com.wlzxj.tunnel.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TARGET_LABEL = "target_label"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTH_KIND = "auth_kind"
        const val EXTRA_AUTH_VALUE = "auth_value"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_ALLOW_INSECURE = "allow_insecure"
        private const val CHANNEL_ID = "tunnel_connection"
        private const val NOTIFICATION_ID = 42

        private fun jsonOptionalString(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.getString(key).takeIf { it.isNotEmpty() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeGeneration: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopActive()
            ACTION_START -> startFromIntent(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (activeGeneration > 0L) {
            TunnelNative.nativeStop(activeGeneration)
            activeGeneration = 0L
        }
        val current = TunnelServiceState.state.value.phase
        if (current == TunnelUiPhase.Connecting ||
            current == TunnelUiPhase.Ready ||
            current == TunnelUiPhase.Stopping
        ) {
            TunnelServiceState.update(TunnelRuntimeState(phase = TunnelUiPhase.Idle))
        }
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
        val server = intent.getStringExtra(EXTRA_SERVER) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL) ?: target
        val name = intent.getStringExtra(EXTRA_NAME)
        val authKind = intent.getStringExtra(EXTRA_AUTH_KIND) ?: "token"
        val authValue = intent.getStringExtra(EXTRA_AUTH_VALUE) ?: return
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val allowInsecure = intent.getBooleanExtra(EXTRA_ALLOW_INSECURE, false)

        activeGeneration = 0L
        TunnelServiceState.update(
            TunnelRuntimeState(
                phase = TunnelUiPhase.Connecting,
                targetDisplay = targetLabel,
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        startForeground(NOTIFICATION_ID, buildNotification("连接中…", targetLabel, null))

        val generation = try {
            TunnelNative.nativeStart(
                server,
                target,
                name,
                authKind,
                authValue,
                username,
                allowInsecure,
                this,
            )
        } catch (e: UnsatisfiedLinkError) {
            finishFailed("原生库加载失败，请重新安装应用")
            return
        } catch (e: Exception) {
            finishFailed("无法启动隧道：${e.message ?: "未知错误"}")
            return
        }

        if (generation < 0) {
            finishFailed("无法启动隧道")
            return
        }
        activeGeneration = generation
    }

    override fun onEvent(json: String) {
        mainHandler.post { handleEvent(json) }
    }

    private fun handleEvent(json: String) {
        val obj = JSONObject(json)
        val generation = obj.optLong("generation")
        if (activeGeneration == 0L || generation != activeGeneration) {
            return
        }
        val rawPhase = obj.optString("phase")
        val phase = when (rawPhase) {
            "connecting" -> TunnelUiPhase.Connecting
            "ready" -> TunnelUiPhase.Ready
            "stopping" -> TunnelUiPhase.Stopping
            "failed" -> TunnelUiPhase.Failed
            "stopped" -> TunnelUiPhase.Idle
            else -> TunnelUiPhase.Idle
        }
        val publicUrl = jsonOptionalString(obj, "public_url")
        val targetDisplay = jsonOptionalString(obj, "target_display")
        val message = jsonOptionalString(obj, "message")
        val prev = TunnelServiceState.state.value
        TunnelServiceState.update(
            prev.copy(
                phase = phase,
                generation = generation,
                publicUrl = publicUrl ?: prev.publicUrl,
                targetDisplay = targetDisplay ?: prev.targetDisplay,
                message = message ?: prev.message,
            ),
        )
        val label = when (phase) {
            TunnelUiPhase.Ready -> publicUrl ?: "已连接"
            TunnelUiPhase.Connecting -> "连接中…"
            TunnelUiPhase.Failed -> message ?: "连接失败"
            TunnelUiPhase.Stopping -> "正在停止…"
            else -> "隧道"
        }
        val displayTarget = targetDisplay ?: prev.targetDisplay ?: ""
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(label, displayTarget, publicUrl))

        if (rawPhase == "stopped" || rawPhase == "failed") {
            activeGeneration = 0L
            TunnelServiceState.update(
                TunnelRuntimeState(
                    phase = if (rawPhase == "failed") TunnelUiPhase.Failed else TunnelUiPhase.Idle,
                    generation = generation,
                    message = if (rawPhase == "failed") message else null,
                    publicUrl = if (rawPhase == "failed") publicUrl else null,
                    targetDisplay = if (rawPhase == "failed") displayTarget else null,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishFailed(message: String) {
        activeGeneration = 0L
        TunnelServiceState.update(TunnelRuntimeState(phase = TunnelUiPhase.Failed, message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopActive() {
        if (activeGeneration > 0L) {
            TunnelServiceState.update(
                TunnelServiceState.state.value.copy(phase = TunnelUiPhase.Stopping),
            )
            TunnelNative.nativeStop(activeGeneration)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(status: String, target: String, publicUrl: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, TunnelForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append("目标: $target")
            if (!publicUrl.isNullOrBlank()) append("\n$publicUrl")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(status)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop_tunnel), stopPending)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_tunnel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
