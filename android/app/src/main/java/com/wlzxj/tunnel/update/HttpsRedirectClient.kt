package com.wlzxj.tunnel.update

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Follows redirects manually, enforcing HTTPS and allowed GitHub hosts on every hop.
 * The in-flight [Call] stays cancellable until the response body is fully closed.
 */
class HttpsRedirectClient(
    private val hostPolicy: DownloadHostPolicy = GithubDownloadHostPolicy,
    client: OkHttpClient? = null,
    private val maxRedirects: Int = 8,
) {
    private val client: OkHttpClient = (client ?: OkHttpClient.Builder()
        .connectTimeout(AppUpdateConstants.DOWNLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AppUpdateConstants.DOWNLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(AppUpdateConstants.DOWNLOAD_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build())

    private val activeCall = AtomicReference<Call?>(null)

    fun cancelInFlight() {
        activeCall.get()?.cancel()
    }

    @Throws(IOException::class)
    fun executeWithRedirects(
        initialUrl: String,
        configure: (Request.Builder) -> Request.Builder = { it },
    ): Response {
        var current = try {
            SecureGithubUrl.parseHttps(initialUrl, hostPolicy).toString()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "下载链接无效", e)
        }
        var redirects = 0
        while (true) {
            val request = configure(Request.Builder().url(current)).build()
            val call = client.newCall(request)
            activeCall.set(call)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                activeCall.compareAndSet(call, null)
                throw e
            }
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                activeCall.compareAndSet(call, null)
                if (location == null) {
                    throw IOException("重定向缺少目标地址")
                }
                if (redirects++ >= maxRedirects) throw IOException("重定向次数过多")
                current = try {
                    SecureGithubUrl.parseHttps(
                        resolveRelative(current, location),
                        hostPolicy,
                    ).toString()
                } catch (e: IllegalArgumentException) {
                    throw IOException(e.message ?: "重定向目标无效", e)
                }
                continue
            }
            return attachCallToBody(response, call)
        }
    }

    private fun attachCallToBody(response: Response, call: Call): Response {
        val body = response.body
        if (body == null) {
            activeCall.compareAndSet(call, null)
            return response
        }
        return response.newBuilder()
            .body(CallBoundResponseBody(body, call, activeCall))
            .build()
    }

    private fun resolveRelative(base: String, location: String): String {
        if (location.startsWith("https://", ignoreCase = true)) return location
        try {
            val baseUri = URI(base)
            return baseUri.resolve(location).toString()
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("重定向目标无效", e)
        }
    }

    private class CallBoundResponseBody(
        private val delegate: ResponseBody,
        private val call: Call,
        private val activeCall: AtomicReference<Call?>,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun source(): BufferedSource = delegate.source()

        override fun close() {
            delegate.close()
            activeCall.compareAndSet(call, null)
        }
    }
}
