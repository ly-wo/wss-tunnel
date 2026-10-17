package com.wlzxj.tunnel.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wlzxj.tunnel.TunnelApp
import com.wlzxj.tunnel.data.AccountIdentity
import com.wlzxj.tunnel.data.ApiException
import com.wlzxj.tunnel.data.AppPreferences
import com.wlzxj.tunnel.data.AuditEvent
import com.wlzxj.tunnel.data.ConnectionPrefs
import com.wlzxj.tunnel.data.DomainRow
import com.wlzxj.tunnel.data.MeProfile
import com.wlzxj.tunnel.data.SecureTokenStore
import com.wlzxj.tunnel.data.TunnelApi
import com.wlzxj.tunnel.data.Validation
import com.wlzxj.tunnel.service.TunnelForegroundService
import com.wlzxj.tunnel.service.TunnelRuntimeState
import com.wlzxj.tunnel.service.TunnelServiceOwnership
import com.wlzxj.tunnel.service.TunnelServiceState
import com.wlzxj.tunnel.service.TunnelUiPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val loginBinding = LoginCredentialBinding()
    private var connectionPrepInFlight = false
    private var activeTunnelOwnership: TunnelOwnership? = null
    private var accountModePreferredOnCredEpoch = false
    private var manualTokenPreferredOnCredEpoch = false
    private var sessionExpiryJob: Job? = null

    fun currentEpochSnapshot(): EpochSnapshot = snapshot()

    fun validatePendingTunnelStart(pending: PendingTunnelStart): Boolean {
        if (isEpochStale(pending.snapshot, serverEpoch, accountEpoch)) return false
        return isStartRequestStillAllowed(pending.request)
    }

    fun initialConnectAuthMode(loggedIn: Boolean): ConnectAuthMode {
        when {
            manualTokenPreferredOnCredEpoch -> {
                manualTokenPreferredOnCredEpoch = false
                return ConnectAuthMode.ManualToken
            }
            accountModePreferredOnCredEpoch && loggedIn -> {
                accountModePreferredOnCredEpoch = false
                return ConnectAuthMode.CurrentAccount
            }
            else -> return if (loggedIn) ConnectAuthMode.CurrentAccount else ConnectAuthMode.ManualToken
        }
    }

    fun onUiVisible() {
        expireSessionIfNeeded()
    }

    fun onPendingTunnelStartDiscarded() {
        // Activity cleared a queued start (e.g. after logout); no secrets retained in VM beyond request.
    }

    fun noteTunnelStarted(ownership: TunnelOwnership) {
        activeTunnelOwnership = ownership
    }

    fun syncActiveOwnershipFromService() {
        val svc = TunnelServiceState.state.value
        activeTunnelOwnership = when (val o = svc.ownership) {
            null -> if (svc.phase == TunnelUiPhase.Idle || svc.phase == TunnelUiPhase.Failed) null else activeTunnelOwnership
            is TunnelServiceOwnership.Manual -> TunnelOwnership.Manual
            is TunnelServiceOwnership.Account -> TunnelOwnership.Account(o.serverOrigin, o.username, o.connectionId)
        }
    }

    private fun snapshot(): EpochSnapshot =
        EpochSnapshot(serverEpoch, accountEpoch, TunnelStartGuard.currentGeneration())

    private fun bumpAccountIdentity() {
        accountEpoch++
        TunnelStartGuard.invalidate()
        loginBinding.clear()
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

    private val _accountSessionCheckInFlight = MutableStateFlow(false)
    val accountSessionCheckInFlight: StateFlow<Boolean> = _accountSessionCheckInFlight.asStateFlow()

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
        TunnelStartGuard.invalidate()
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
                val profile = api.me(origin, prefs.debugAllowLocalHttp, sessionToken)
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                bumpAccountIdentity()
                val canonicalUser = profile.username
                _session.value = SessionState(sessionToken, expires, canonicalUser, origin)
                loginBinding.bind(origin, canonicalUser, password, accountEpoch)
                scheduleSessionExpiry(expires)
                accountModePreferredOnCredEpoch = true
                manualTokenPreferredOnCredEpoch = false
                _credentialsEpoch.value++
                _injectedConnectionToken.value = ""
                loadAccountInternal(snapshot())
            } catch (e: ApiException) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isEpochStale(epoch, serverEpoch, accountEpoch)) return@launch
                _account.value = _account.value.copy(loading = false, message = e.message)
            } finally {
                if (!isEpochStale(epoch, serverEpoch, accountEpoch) && _session.value.session == null) {
                    _account.value = _account.value.copy(loading = false)
                }
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
        clearSession(keepMessage = logoutMessage())
        if (token != null && origin != null) {
            viewModelScope.launch {
                runCatching { api.logout(origin, prefs.debugAllowLocalHttp, token) }
            }
        }
    }

    private fun logoutMessage(): String {
        syncActiveOwnershipFromService()
        return if (hasAccountLinkedTunnel()) {
            "已退出。与该账号关联的隧道将停止。"
        } else {
            "已退出。"
        }
    }

    private fun hasAccountLinkedTunnel(): Boolean {
        if (activeTunnelOwnership is TunnelOwnership.Account) return true
        return TunnelServiceState.state.value.ownership is TunnelServiceOwnership.Account
    }

    private fun clearSession(keepMessage: String? = null) {
        val accountToStop = resolveAccountTunnelToStop()
        bumpAccountIdentity()
        _session.value = SessionState()
        _account.value = AccountUiState(
            registrationEnabled = _account.value.registrationEnabled,
            message = keepMessage,
            oneTimeToken = null,
        )
        _domains.value = DomainsUiState()
        if (accountToStop != null) {
            requestStopAccountTunnel(accountToStop)
        }
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        activeTunnelOwnership = null
    }

    private fun resolveAccountTunnelToStop(): TunnelOwnership.Account? {
        syncActiveOwnershipFromService()
        return when (val o = activeTunnelOwnership) {
            is TunnelOwnership.Account -> o
            else -> {
                val svc = TunnelServiceState.state.value.ownership
                if (svc is TunnelServiceOwnership.Account) {
                    TunnelOwnership.Account(svc.serverOrigin, svc.username, svc.connectionId)
                } else {
                    null
                }
            }
        }
    }

    private fun requestStopAccountTunnel(account: TunnelOwnership.Account) {
        val intent = Intent(getApplication(), TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP_ACCOUNT
            putExtra(TunnelServiceOwnership.EXTRA_OWNERSHIP_SERVER, account.serverOrigin)
            putExtra(TunnelServiceOwnership.EXTRA_OWNERSHIP_USER, account.username)
            putExtra(TunnelServiceOwnership.EXTRA_CONNECTION_ID, account.connectionId)
        }
        getApplication<Application>().startService(intent)
    }

    private fun scheduleSessionExpiry(expiresAtEpochSeconds: Long?) {
        sessionExpiryJob?.cancel()
        if (expiresAtEpochSeconds == null) return
        val delayMs = expiresAtEpochSeconds * 1000L - System.currentTimeMillis()
        sessionExpiryJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            expireSessionIfNeeded()
        }
    }

    private fun expireSessionIfNeeded(): Boolean {
        val session = _session.value
        if (session.session == null) return false
        if (!isSessionExpired(session.expiresAt)) return false
        clearSession(keepMessage = "登录已过期，请重新登录")
        _connectError.value = "登录已过期，请重新登录"
        return true
    }

    private fun isStartRequestStillAllowed(request: TunnelStartRequest): Boolean {
        if (expireSessionIfNeeded()) return false
        val ownership = request.ownership
        if (ownership is TunnelOwnership.Manual) return true
        val session = _session.value
        val token = session.session
        val origin = session.serverOrigin
        val user = session.username
        if (token == null || origin == null || user == null) return false
        if (ownership !is TunnelOwnership.Account) return false
        if (ownership.serverOrigin != origin) return false
        if (!AccountIdentity.sameUser(ownership.username, user)) return false
        if (loginBinding.tunnelPassword(origin, user, accountEpoch) == null) return false
        val prefs = connectionPrefs.value
        val server = runCatching {
            Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
        }.getOrNull() ?: return false
        return server == origin
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
        manualTokenPreferredOnCredEpoch = true
        accountModePreferredOnCredEpoch = false
        _credentialsEpoch.value++
        dismissOneTimeToken()
        _account.value = _account.value.copy(message = "已将新 Token 填入连接页（手动 Token 模式），请开始连接")
    }

    fun clearInjectedConnectionToken() {
        _injectedConnectionToken.value = ""
    }

    fun beginConnection(
        mode: ConnectAuthMode,
        targetInput: String,
        tokenInput: String,
        manualUsername: String,
        manualPassword: String,
        onReady: (TunnelStartRequest) -> Unit,
    ) {
        if (connectionPrepInFlight || isTunnelBusy()) return
        clearConnectError()
        when (mode) {
            ConnectAuthMode.CurrentAccount -> {
                connectionPrepInFlight = true
                val epoch = snapshot()
                viewModelScope.launch {
                    try {
                        if (expireSessionIfNeeded()) return@launch
                        val req = prepareAccountConnection(epoch, targetInput)
                        if (req != null &&
                            !isEpochStale(epoch, serverEpoch, accountEpoch) &&
                            isStartRequestStillAllowed(req)
                        ) {
                            onReady(req)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _connectError.value = e.message ?: "无法准备连接"
                    } finally {
                        connectionPrepInFlight = false
                    }
                }
            }
            ConnectAuthMode.ManualToken,
            ConnectAuthMode.ManualPassword,
            -> {
                val req = prepareManualConnection(mode, targetInput, tokenInput, manualUsername, manualPassword)
                if (req != null) onReady(req)
            }
        }
    }

    private suspend fun prepareAccountConnection(epoch: EpochSnapshot, targetInput: String): TunnelStartRequest? {
        if (expireSessionIfNeeded()) return null
        val session = _session.value
        val token = session.session
        val origin = session.serverOrigin
        val username = session.username
        if (token == null || origin == null || username == null) {
            _connectError.value = "请先登录账号"
            return null
        }
        if (isSessionExpired(session.expiresAt)) {
            clearSession(keepMessage = "登录已过期，请重新登录")
            _connectError.value = "登录已过期，请重新登录"
            return null
        }
        val prefs = connectionPrefs.value
        val server = try {
            Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
        } catch (e: Exception) {
            _connectError.value = e.message
            return null
        }
        val targetForTunnel = targetInput.trim()
        try {
            Validation.normalizeTarget(targetForTunnel)
        } catch (e: Exception) {
            _connectError.value = e.message
            return null
        }
        if (server != origin) {
            _connectError.value = "接入服务器与当前登录账号不一致，请重新登录"
            return null
        }
        val boundPassword = loginBinding.tunnelPassword(origin, username, accountEpoch)
        if (boundPassword == null) {
            _connectError.value = "请重新登录以建立隧道连接"
            return null
        }
        _accountSessionCheckInFlight.value = true
        val profile = try {
            api.me(origin, prefs.debugAllowLocalHttp, token)
        } catch (e: ApiException) {
            if (isEpochStale(epoch, serverEpoch, accountEpoch)) return null
            if (e.status == 401) {
                clearSession(keepMessage = e.message)
                _connectError.value = e.message ?: "登录已失效，请重新登录"
            } else {
                _connectError.value = "会话校验失败：${e.message}（可重试）"
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isEpochStale(epoch, serverEpoch, accountEpoch)) return null
            _connectError.value = "会话校验失败：${e.message ?: "网络错误"}（可重试）"
            return null
        } finally {
            _accountSessionCheckInFlight.value = false
        }
        if (isEpochStale(epoch, serverEpoch, accountEpoch)) return null
        if (!AccountIdentity.sameUser(profile.username, username)) {
            _connectError.value = "账号身份已变化，请重新登录"
            return null
        }
        val canonicalUser = profile.username
        val passwordAfter = loginBinding.tunnelPassword(origin, canonicalUser, accountEpoch)
        if (passwordAfter == null) {
            _connectError.value = "请重新登录以建立隧道连接"
            return null
        }
        val connectionId = TunnelStartGuard.nextConnectionId()
        return try {
            buildTunnelStartRequest(
                server = server,
                prefs = prefs,
                targetOverride = targetForTunnel,
                authKind = "password",
                authValue = passwordAfter,
                username = canonicalUser,
                ownership = TunnelOwnership.Account(origin, canonicalUser, connectionId),
                startSnapshot = snapshot(),
            )
        } catch (e: Exception) {
            _connectError.value = e.message
            null
        }
    }

    private fun prepareManualConnection(
        mode: ConnectAuthMode,
        targetInput: String,
        tokenInput: String,
        manualUsername: String,
        manualPassword: String,
    ): TunnelStartRequest? {
        val prefs = connectionPrefs.value
        return try {
            val server = Validation.requireHttpsForRelease(prefs.server, prefs.debugAllowLocalHttp)
            val (kind, value, user) = when (mode) {
                ConnectAuthMode.ManualPassword -> Triple("password", manualPassword, manualUsername)
                ConnectAuthMode.ManualToken -> {
                    val token = tokenInput.ifBlank { secure.loadToken(server) ?: "" }
                    require(token.length >= 32) { "连接 Token 无效" }
                    Triple("token", token, "")
                }
                ConnectAuthMode.CurrentAccount -> error("unreachable")
            }
            if (prefs.saveConnectionToken && kind == "token") {
                if (!secure.saveToken(server, value)) {
                    _connectNotice.value =
                        "连接 Token 未能加密保存到本机，本次仍使用内存中的 Token 建立连接"
                    updateConnection { it.copy(saveConnectionToken = false) }
                }
            }
            buildTunnelStartRequest(
                server = server,
                prefs = prefs,
                targetOverride = targetInput.trim(),
                authKind = kind,
                authValue = value,
                username = user,
                ownership = TunnelOwnership.Manual,
                startSnapshot = snapshot(),
            )
        } catch (e: Exception) {
            _connectError.value = e.message
            null
        }
    }

    private fun buildTunnelStartRequest(
        server: String,
        prefs: ConnectionPrefs,
        targetOverride: String = prefs.target,
        authKind: String,
        authValue: String,
        username: String,
        ownership: TunnelOwnership,
        startSnapshot: EpochSnapshot,
    ): TunnelStartRequest {
        val target = Validation.normalizeTarget(targetOverride)
        val name = Validation.optionalSubdomain(prefs.subdomain)
        return TunnelStartRequest(
            server = server,
            target = target,
            targetLabel = Validation.targetDisplayLabel(target),
            name = name,
            authKind = authKind,
            authValue = authValue,
            username = username,
            allowInsecure = prefs.debugAllowLocalHttp && com.wlzxj.tunnel.BuildConfig.DEBUG &&
                Validation.isLocalDebugHost(server),
            ownership = ownership,
            startSnapshot = startSnapshot,
        )
    }

    override fun onCleared() {
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        resolveAccountTunnelToStop()?.let { requestStopAccountTunnel(it) }
        TunnelStartGuard.invalidate()
        loginBinding.clear()
        super.onCleared()
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
    val ownership: TunnelOwnership = TunnelOwnership.Manual,
    val startSnapshot: EpochSnapshot = EpochSnapshot(0, 0, 0L),
)
