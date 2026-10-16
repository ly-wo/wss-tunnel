package com.wlzxj.tunnel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wlzxj.tunnel.TunnelApp
import com.wlzxj.tunnel.data.ApiException
import com.wlzxj.tunnel.data.AppPreferences
import com.wlzxj.tunnel.data.AuditEvent
import com.wlzxj.tunnel.data.ConnectionPrefs
import com.wlzxj.tunnel.data.DomainRow
import com.wlzxj.tunnel.data.MeProfile
import com.wlzxj.tunnel.data.SecureTokenStore
import com.wlzxj.tunnel.data.TunnelApi
import com.wlzxj.tunnel.data.Validation
import com.wlzxj.tunnel.service.TunnelRuntimeState
import com.wlzxj.tunnel.service.TunnelServiceState
import com.wlzxj.tunnel.service.TunnelUiPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionState(
    val session: String? = null,
    val expiresAt: Long? = null,
    val username: String? = null,
    val serverOrigin: String? = null,
)

data class DomainsUiState(
    val loading: Boolean = false,
    val rows: List<DomainRow> = emptyList(),
    val limit: Int? = null,
    val error: String? = null,
)

data class AccountUiState(
    val registrationEnabled: Boolean? = null,
    val profile: MeProfile? = null,
    val audit: List<AuditEvent> = emptyList(),
    val auditRetention: Int? = null,
    val oneTimeToken: String? = null,
    val message: String? = null,
    val loading: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TunnelApp
    private val prefs: AppPreferences = app.preferences
    private val secure: SecureTokenStore = app.secureTokens
    private val api: TunnelApi = app.api

    private var serverEpoch = 0
    private var accountEpoch = 0

    private fun snapshot(): EpochSnapshot = EpochSnapshot(serverEpoch, accountEpoch)

    private fun bumpAccountIdentity() {
        accountEpoch++
        _credentialsEpoch.value++
        _injectedConnectionToken.value = ""
    }

    val connectionPrefs: StateFlow<ConnectionPrefs> =
        prefs.connectionPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionPrefs())

    val tunnelState: StateFlow<TunnelRuntimeState> = TunnelServiceState.state

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _domains = MutableStateFlow(DomainsUiState())
    val domains: StateFlow<DomainsUiState> = _domains.asStateFlow()

    private val _account = MutableStateFlow(AccountUiState())
    val account: StateFlow<AccountUiState> = _account.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private val _connectNotice = MutableStateFlow<String?>(null)
    val connectNotice: StateFlow<String?> = _connectNotice.asStateFlow()

    private val _credentialsEpoch = MutableStateFlow(0)
    val credentialsEpoch: StateFlow<Int> = _credentialsEpoch.asStateFlow()

    private val _injectedConnectionToken = MutableStateFlow("")
    val injectedConnectionToken: StateFlow<String> = _injectedConnectionToken.asStateFlow()

    val keystoreAvailable: Boolean = secure.keystoreAvailable

    fun clearConnectError() {
        _connectError.value = null
        _connectNotice.value = null
    }

    fun updateConnection(transform: (ConnectionPrefs) -> ConnectionPrefs) {
        if (isTunnelBusy()) return
        viewModelScope.launch { prefs.updateConnection(transform) }
    }

    fun onServerChanged(newServer: String) {
        if (isTunnelBusy()) return
        val old = connectionPrefs.value.server.trim()
        val next = newServer.trim()
        if (old == next) {
            updateConnection { it.copy(server = newServer) }
            return
        }
        serverEpoch++
        bumpAccountIdentity()
        _session.value = SessionState()
        _account.value = AccountUiState()
        _domains.value = DomainsUiState()
        secure.clearAll()
        updateConnection { it.copy(server = newServer) }
    }

    fun onSaveTokenToggled(enabled: Boolean) {
        val prefs = connectionPrefs.value
        if (!enabled) {
            runCatching {
                val origin = Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
                secure.clearToken(origin)
            }
        }
        updateConnection { it.copy(saveConnectionToken = enabled) }
    }

    fun reuseDomainName(name: String) {
        if (isTunnelBusy()) return
        viewModelScope.launch { prefs.setSubdomainForReuse(name) }
    }

    fun refreshConfig() {
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            try {
                val cfg = api.fetchConfig(prefs.server, prefs.debugAllowLocalHttp)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(registrationEnabled = cfg.registrationEnabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(message = e.message)
            }
        }
    }

    fun refreshDomains() {
        val session = _session.value
        val token = session.session
        val origin = session.serverOrigin
        if (token == null || origin == null) {
            _domains.value = DomainsUiState(error = "请先登录账号以查看域名")
            return
        }
        if (_domains.value.loading) return
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            _domains.value = _domains.value.copy(loading = true, error = null)
            try {
                val (rows, limit) = api.domains(origin, prefs.debugAllowLocalHttp, token)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _domains.value = DomainsUiState(rows = rows, limit = limit)
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                if (e.status == 401) clearSession(keepMessage = e.message)
                else _domains.value = DomainsUiState(error = e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _domains.value = DomainsUiState(error = e.message)
            }
        }
    }

    fun deleteOfflineDomain(name: String) {
        val session = _session.value
        val token = session.session ?: return
        val origin = session.serverOrigin ?: return
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            try {
                api.deleteDomain(origin, prefs.debugAllowLocalHttp, token, name)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                refreshDomains()
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                if (e.status == 401) clearSession(keepMessage = e.message)
                else _domains.value = _domains.value.copy(error = e.message)
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _domains.value = _domains.value.copy(error = e.message)
            }
        }
    }

    fun login(username: String, password: String) {
        if (_account.value.loading) return
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            _account.value = _account.value.copy(loading = true, message = null)
            try {
                val origin = Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
                val (sessionToken, expires) = api.login(origin, prefs.debugAllowLocalHttp, username, password)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _session.value = SessionState(sessionToken, expires, username, origin)
                loadAccountInternal(epoch)
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            }
        }
    }

    fun register(username: String, password: String) {
        if (_account.value.loading) return
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            _account.value = _account.value.copy(loading = true, message = null)
            try {
                api.register(prefs.server, prefs.debugAllowLocalHttp, username, password)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = "注册成功，请登录")
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            }
        }
    }

    fun logout() {
        val captured = _session.value
        val token = captured.session
        val origin = captured.serverOrigin
        val prefs = connectionPrefs.value
        clearSession(keepMessage = "已退出。进行中的隧道仍可在通知栏停止。")
        if (token != null && origin != null) {
            viewModelScope.launch {
                runCatching { api.logout(origin, prefs.debugAllowLocalHttp, token) }
            }
        }
    }

    private fun clearSession(keepMessage: String? = null) {
        bumpAccountIdentity()
        _session.value = SessionState()
        _account.value = AccountUiState(
            registrationEnabled = _account.value.registrationEnabled,
            message = keepMessage,
            oneTimeToken = null,
        )
        _domains.value = DomainsUiState()
    }

    fun loadAccount() {
        loadAccountInternal(snapshot())
    }

    private fun loadAccountInternal(epoch: EpochSnapshot) {
        val session = _session.value
        val token = session.session ?: return
        val origin = session.serverOrigin ?: return
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            _account.value = _account.value.copy(loading = true)
            try {
                val profile = api.me(origin, prefs.debugAllowLocalHttp, token)
                val (audit, retention) = api.audit(origin, prefs.debugAllowLocalHttp, token)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(
                    loading = false,
                    profile = profile,
                    audit = audit,
                    auditRetention = retention,
                    message = null,
                )
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                if (e.status == 401) clearSession(keepMessage = e.message)
                else _account.value = _account.value.copy(loading = false, message = e.message)
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            }
        }
    }

    fun rotateToken() {
        val session = _session.value
        val token = session.session ?: return
        val origin = session.serverOrigin ?: return
        val epoch = snapshot()
        val prefs = connectionPrefs.value
        viewModelScope.launch {
            try {
                val (newToken, _) = api.createToken(origin, prefs.debugAllowLocalHttp, token)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(oneTimeToken = newToken, message = "请立即保存新 Token")
                loadAccountInternal(epoch)
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                if (e.status == 401) clearSession(keepMessage = e.message)
                else _account.value = _account.value.copy(message = e.message)
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(message = e.message)
            }
        }
    }

    fun dismissOneTimeToken() {
        _account.value = _account.value.copy(oneTimeToken = null)
    }

    fun applyOneTimeTokenToConnection(token: String) {
        _injectedConnectionToken.value = token
        _credentialsEpoch.value++
        dismissOneTimeToken()
        _account.value = _account.value.copy(message = "已将新 Token 填入连接页，请手动开始连接")
    }

    fun clearInjectedConnectionToken() {
        _injectedConnectionToken.value = ""
    }

    fun prepareConnection(
        tokenInput: String,
        usePassword: Boolean,
        username: String,
        password: String,
    ): TunnelStartRequest? {
        val prefs = connectionPrefs.value
        return try {
            val server = Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
            val target = Validation.normalizeTarget(prefs.target)
            val name = Validation.optionalSubdomain(prefs.subdomain)
            val (kind, value, user) = if (usePassword) {
                Triple("password", password, username)
            } else {
                val token = tokenInput.ifBlank { secure.loadToken(server) ?: "" }
                require(token.length >= 32) { "连接 Token 无效" }
                Triple("token", token, "")
            }
            if (prefs.saveConnectionToken && kind == "token") {
                if (!secure.saveToken(server, value)) {
                    _connectNotice.value =
                        "连接 Token 未能加密保存到本机，本次仍使用内存中的 Token 建立连接"
                    updateConnection { it.copy(saveConnectionToken = false) }
                }
            }
            TunnelStartRequest(
                server = server,
                target = target,
                targetLabel = Validation.targetDisplayLabel(target),
                name = name,
                authKind = kind,
                authValue = value,
                username = user,
                allowInsecure = prefs.debugAllowLocalHttp && com.wlzxj.tunnel.BuildConfig.DEBUG &&
                    Validation.isLocalDebugHost(server),
            )
        } catch (e: Exception) {
            _connectError.value = e.message
            null
        }
    }

    fun isTunnelBusy(): Boolean {
        val phase = tunnelState.value.phase
        return phase == TunnelUiPhase.Connecting || phase == TunnelUiPhase.Ready || phase == TunnelUiPhase.Stopping
    }
}

data class TunnelStartRequest(
    val server: String,
    val target: String,
    val targetLabel: String,
    val name: String?,
    val authKind: String,
    val authValue: String,
    val username: String,
    val allowInsecure: Boolean,
)
