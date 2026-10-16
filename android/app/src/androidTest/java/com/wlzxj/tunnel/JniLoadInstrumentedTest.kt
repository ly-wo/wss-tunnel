package com.wlzxj.tunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wlzxj.tunnel.jni.TunnelNative
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JniLoadInstrumentedTest {
    @Test
    fun nativeLibraryLoads() {
        assertFalse(TunnelNative.nativeIsRunning())
    }
}
