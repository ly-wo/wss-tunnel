package com.wlzxj.tunnel.update

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

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
