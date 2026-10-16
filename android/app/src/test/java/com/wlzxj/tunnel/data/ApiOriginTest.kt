package com.wlzxj.tunnel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiOriginTest {
    @Test
    fun normalizesHttpsOrigin() {
        assertEquals(
            "https://connect.example.com",
            ApiOrigin.resolve("https://connect.example.com/", debugAllowLocalHttp = false),
        )
    }

    @Test
    fun rejectsPathOnOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiOrigin.resolve("https://connect.example.com/foo", debugAllowLocalHttp = false)
        }
    }
}
