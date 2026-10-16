package com.wlzxj.tunnel

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner

class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, TunnelApp::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                target.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }
}
