package com.wlzxj.tunnel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

data class ServerConfig(
    val registrationEnabled: Boolean,
    val domainLimit: Int,
)

data class MeProfile(
    val username: String,
    val tokenPrefix: String?,
    val tokenCreatedAt: Long?,
    val domainLimit: Int,
    val isAdmin: Boolean,
    val auditRetentionDays: Int,
)

data class DomainRow(
    val name: String,
    val domain: String,
    val status: String,
    val createdAt: Long,
    val lastConnectedAt: Long?,
)

data class AuditEvent(
    val id: Long,
    val action: String,
    val outcome: String,
    val domain: String?,
    val createdAt: Long,
)

class TunnelApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val jsonType = "application/json".toMediaType()

    private fun apiBase(origin: String): String = "${origin.trim().removeSuffix("/")}/_tunnel/api"

    private suspend fun request(
        origin: String,
        path: String,
        method: String,
        session: String? = null,
        body: JSONObject? = null,
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${apiBase(origin)}/$path")
            .method(
                method,
                when {
                    body != null -> body.toString().toRequestBody(jsonType)
                    method == "POST" -> JSONObject().toString().toRequestBody(jsonType)
                    else -> null
                },
            )
            .header("Accept", "application/json")
        if (session != null) {
            builder.header("Authorization", "Bearer $session")
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful && response.code != 204) {
                    val err = text?.let {
                        runCatching { JSONObject(it).optString("error") }.getOrNull()
                    }
                    throw ApiException(response.code, err ?: "请求失败 (${response.code})")
                }
                response.code to text
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw IOException("网络错误：${e.message ?: "请检查连接"}", e)
        } catch (e: Exception) {
            throw IOException("请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    suspend fun fetchConfig(origin: String, debugAllowLocalHttp: Boolean): ServerConfig {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val (_, text) = request(base, "config", "GET")
        val json = JSONObject(text ?: "{}")
        return ServerConfig(
            registrationEnabled = json.optBoolean("registration"),
            domainLimit = json.optInt("domain_limit", 0),
        )
    }

    suspend fun register(
        origin: String,
        debugAllowLocalHttp: Boolean,
        username: String,
        password: String,
    ): String {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val body = JSONObject().put("username", username).put("password", password)
        val (_, text) = request(base, "register", "POST", body = body)
        return JSONObject(text ?: "{}").optString("username")
    }

    suspend fun login(
        origin: String,
        debugAllowLocalHttp: Boolean,
        username: String,
        password: String,
    ): Pair<String, Long> {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val body = JSONObject().put("username", username).put("password", password)
        val (_, text) = request(base, "login", "POST", body = body)
        val json = JSONObject(text ?: "{}")
        return json.getString("session") to json.getLong("expires_at")
    }

    suspend fun logout(origin: String, debugAllowLocalHttp: Boolean, session: String) {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        runCatching { request(base, "logout", "POST", session = session) }
    }

    suspend fun me(origin: String, debugAllowLocalHttp: Boolean, session: String): MeProfile {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val (_, text) = request(base, "me", "GET", session = session)
        val json = JSONObject(text ?: "{}")
        return MeProfile(
            username = json.getString("username"),
            tokenPrefix = json.optString("token_prefix").ifEmpty { null },
            tokenCreatedAt = if (json.isNull("token_created_at")) null else json.getLong("token_created_at"),
            domainLimit = json.getInt("domain_limit"),
            isAdmin = json.optBoolean("is_admin"),
            auditRetentionDays = json.getInt("audit_retention_days"),
        )
    }

    suspend fun createToken(origin: String, debugAllowLocalHttp: Boolean, session: String): Pair<String, String> {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val (_, text) = request(base, "token", "POST", session = session, body = JSONObject())
        val json = JSONObject(text ?: "{}")
        return json.getString("token") to json.getString("prefix")
    }

    suspend fun domains(
        origin: String,
        debugAllowLocalHttp: Boolean,
        session: String,
    ): Pair<List<DomainRow>, Int> {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val (_, text) = request(base, "domains", "GET", session = session)
        val json = JSONObject(text ?: "{}")
        val limit = json.getInt("limit")
        val rows = json.getJSONArray("domains")
        val list = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                add(
                    DomainRow(
                        name = row.getString("name"),
                        domain = row.getString("domain"),
                        status = row.getString("status"),
                        createdAt = row.getLong("created_at"),
                        lastConnectedAt = if (row.isNull("last_connected_at")) null else row.getLong("last_connected_at"),
                    ),
                )
            }
        }
        return list to limit
    }

    suspend fun deleteDomain(
        origin: String,
        debugAllowLocalHttp: Boolean,
        session: String,
        name: String,
    ) {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        request(base, "domains/$name", "DELETE", session = session)
    }

    suspend fun audit(
        origin: String,
        debugAllowLocalHttp: Boolean,
        session: String,
    ): Pair<List<AuditEvent>, Int> {
        val base = ApiOrigin.resolve(origin, debugAllowLocalHttp)
        val (_, text) = request(base, "audit", "GET", session = session)
        val json = JSONObject(text ?: "{}")
        val retention = json.getInt("retention_days")
        val rows = json.getJSONArray("events")
        val list = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                add(
                    AuditEvent(
                        id = row.getLong("id"),
                        action = row.getString("action"),
                        outcome = row.getString("outcome"),
                        domain = row.optString("domain").ifEmpty { null },
                        createdAt = row.getLong("created_at"),
                    ),
                )
            }
        }
        return list to retention
    }
}
