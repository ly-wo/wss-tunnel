package com.wlzxj.tunnel.update

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * Rewrites outbound GitHub API/release URLs to a local [MockWebServer] while leaving
 * product URL validation unchanged (URLs in JSON remain real github.com paths).
 */
object MockGithubTransport {
    fun okhttpClient(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(rewriteTo(server))
            .build()

    private fun rewriteTo(server: MockWebServer): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val query = request.url.query
        val local = server.url(
            if (query != null) "$path?$query" else path,
        )
        chain.proceed(
            request.newBuilder()
                .url(local)
                .build(),
        )
    }
}
