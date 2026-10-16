package com.wlzxj.tunnel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    @Test
    fun normalizesTargetRoot() {
        assertEquals(
            "http://127.0.0.1:3000",
            Validation.normalizeTarget("http://127.0.0.1:3000"),
        )
    }

    @Test
    fun rejectsInvalidSubdomain() {
        assertThrows(IllegalArgumentException::class.java) {
            Validation.optionalSubdomain("Bad_Name")
        }
    }

    @Test
    fun acceptsValidSubdomain() {
        assertEquals("demo", Validation.optionalSubdomain("demo"))
    }
}
