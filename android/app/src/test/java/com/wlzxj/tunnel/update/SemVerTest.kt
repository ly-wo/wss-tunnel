package com.wlzxj.tunnel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun parsesStableTags() {
        assertEquals(SemVer(1, 2, 3), SemVer.parseTag("v1.2.3"))
        assertEquals(SemVer(0, 2, 3), SemVer.parseTag("0.2.3"))
    }

    @Test
    fun rejectsInvalidTags() {
        assertNull(SemVer.parseTag("v1.2"))
        assertNull(SemVer.parseTag("v1.2.3-beta"))
        assertNull(SemVer.parseTag("release"))
    }

    @Test
    fun comparesNumerically() {
        assertTrue(SemVer(0, 2, 4) > SemVer(0, 2, 3))
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 9, 9))
        assertEquals(0, SemVer(1, 2, 3).compareTo(SemVer(1, 2, 3)))
    }
}
