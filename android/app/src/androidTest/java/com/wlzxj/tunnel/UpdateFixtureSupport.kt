package com.wlzxj.tunnel

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

object UpdateFixtureSupport {
    const val GOOD_FIXTURE = "files/update-fixture.apk"
    const val WRONG_SIGNATURE_FIXTURE = "files/update-wrong-signature.apk"

    private const val INSTALLER_UI_WAIT_MS = 30_000L
    private const val INSTALLER_REQUEST_CODE = 0x55_01

    fun fixtureFile(context: Context, relativePath: String): File =
        File(context.applicationInfo.dataDir, relativePath)

    fun requireFixture(context: Context, relativePath: String): File {
        val file = fixtureFile(context, relativePath)
        if (!file.isFile || file.length() <= 0L) {
            throw AssertionError(
                "缺少设备 fixture：${file.absolutePath}（请先 adb push 并用 run-as cp 注入）",
            )
        }
        return file
    }

    /**
     * When `-e launch_update_installer true`, starts the installer from a foreground [MainActivity]
     * via [android.app.Activity.startActivityForResult] (same entry style as the product launcher),
     * waits for Codex to capture the system UI, then closes the scenario.
     */
    fun launchInstallerIfRequested(intent: Intent?) {
        if (intent == null) return
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("launch_update_installer") != "true") return

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, INSTALLER_REQUEST_CODE)
            }
            Thread.sleep(INSTALLER_UI_WAIT_MS)
        }
    }

    fun setRequestInstallPackages(context: Context, allow: Boolean) {
        val mode = if (allow) "allow" else "deny"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(
            "appops set ${context.packageName} REQUEST_INSTALL_PACKAGES $mode",
        ).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                input.readBytes()
            }
        }
    }
}
