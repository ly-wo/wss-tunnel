package com.wlzxj.tunnel

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.wlzxj.tunnel.data.AppPreferences
import kotlinx.coroutines.runBlocking

class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, TunnelApp::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                target.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val origin = arguments?.getString("tunnel_origin")
        if (origin != null) {
            val fixtureTarget = arguments.getString("tunnel_target") ?: "http://127.0.0.1:19093"
            runBlocking {
                AppPreferences(target).updateConnection {
                    it.copy(
                        server = origin,
                        target = fixtureTarget,
                        debugAllowLocalHttp = true,
                    )
                }
            }
        }
    }
}
