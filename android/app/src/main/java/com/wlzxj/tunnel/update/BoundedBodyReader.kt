package com.wlzxj.tunnel.update

import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream

internal object BoundedBodyReader {
    @Throws(IOException::class)
    fun readUtf8(body: ResponseBody, maxBytes: Int): String? {
        val bytes = readBytes(body, maxBytes) ?: return null
        return bytes.decodeToString()
    }

    @Throws(IOException::class)
    fun readBytes(body: ResponseBody, maxBytes: Int): ByteArray? {
        val declared = body.contentLength()
        if (declared > maxBytes) return null
        body.byteStream().use { stream ->
            return readBounded(stream, maxBytes, declared)
        }
    }

    @Throws(IOException::class)
    fun drainWithLimit(body: ResponseBody, maxBytes: Long, onChunk: (Long) -> Unit): Boolean {
        val declared = body.contentLength()
        if (declared > maxBytes) return false
        body.byteStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return false
                onChunk(total)
            }
            if (declared >= 0 && total != declared) return false
            return true
        }
    }

    @Throws(IOException::class)
    private fun readBounded(input: InputStream, maxBytes: Int, declaredLength: Long): ByteArray? {
        val buffer = ByteArray(minOf(maxBytes, 32 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        if (declaredLength >= 0 && total.toLong() != declaredLength) return null
        return out.toByteArray()
    }
}
