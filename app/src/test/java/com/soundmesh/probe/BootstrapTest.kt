package com.soundmesh.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun packageNameIsStable() {
        assertEquals("com.soundmesh.probe", ProbeIdentity.APPLICATION_ID)
    }
}
