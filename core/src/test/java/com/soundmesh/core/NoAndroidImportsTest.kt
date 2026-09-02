package com.soundmesh.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure logic modules must stay testable on a plain JVM. Android imports are the one
 * mistake that silently breaks that, so the rule is enforced rather than documented.
 */
class NoAndroidImportsTest {
    @Test
    fun mainSourcesNeverImportAndroidApis() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val offenders = sources.filter { file ->
            file.readLines().any { it.trimStart().startsWith("import android.") }
        }.map { it.name }.toList()

        assertEquals("these files must not reference Android APIs", emptyList<String>(), offenders)
    }

    @Test
    fun theGuardActuallyScansSomething() {
        val count = File("src/main/java").walkTopDown().count { it.extension == "kt" }
        assertEquals(true, count > 0)
    }
}
