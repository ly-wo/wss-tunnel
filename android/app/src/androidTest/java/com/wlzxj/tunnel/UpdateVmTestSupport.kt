package com.wlzxj.tunnel

import android.content.Context
import com.wlzxj.tunnel.update.MockGithubTransport
import com.wlzxj.tunnel.update.UpdateCheckPhase
import com.wlzxj.tunnel.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer

object UpdateVmTestSupport {
    suspend fun downloadUpdateViaViewModel(
        context: Context,
        server: MockWebServer,
        payload: ByteArray,
        tag: String = "v0.2.5",
    ): UpdateViewModel {
        return withContext(Dispatchers.Main) {
            val vm = UpdateViewModel.create(
                context.applicationContext as android.app.Application,
                MockGithubTransport.okhttpClient(server),
            )
            vm.checkForUpdate(manual = true)
            waitUntil("等待发现新版本") {
                vm.uiState.value.phase == UpdateCheckPhase.UpdateAvailable
            }
            vm.downloadUpdate()
            waitUntil("等待下载完成") { vm.uiState.value.downloadedApkReady }
            vm
        }
    }

    suspend fun waitUntil(message: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(50)
        }
        throw AssertionError(message)
    }
}
