package com.soundmesh.probe.sync

import com.soundmesh.probe.PlaybackUsage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredOutputLeadTest {
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("soundmesh-lead-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** An unmeasured handset corrects nothing, rather than borrowing another handset's number. */
    @Test
    fun anUnmeasuredOutputHasNoLead() = withDirectory { directory ->
        assertNull(StoredOutputLead(directory, PlaybackUsage.ACCESSIBILITY).read())
    }

    /** Media is the reference every other output is measured against, so it leads itself by nothing. */
    @Test
    fun theReferenceOutputLeadsItselfByNothing() = withDirectory { directory ->
        StoredOutputLead(directory, PlaybackUsage.MEDIA).write(9_999L)

        assertEquals(0L, StoredOutputLead(directory, PlaybackUsage.MEDIA).read())
    }

    @Test
    fun aMeasuredLeadIsReadBackAsItWasWritten() = withDirectory { directory ->
        StoredOutputLead(directory, PlaybackUsage.ACCESSIBILITY).write(19_482L)

        assertEquals(19_482L, StoredOutputLead(directory, PlaybackUsage.ACCESSIBILITY).read())
    }

    @Test
    fun eachOutputIsMeasuredOnItsOwn() = withDirectory { directory ->
        StoredOutputLead(directory, PlaybackUsage.ACCESSIBILITY).write(19_482L)

        assertNull(StoredOutputLead(directory, PlaybackUsage.ALARM).read())
    }

    /** Half a number applied to every chunk of a run is worse than no correction at all. */
    @Test
    fun anUnreadableLeadIsNoLead() = withDirectory { directory ->
        File(directory, "${StoredOutputLead.FILE_PREFIX}ACCESSIBILITY").writeText("nineteen")

        assertNull(StoredOutputLead(directory, PlaybackUsage.ACCESSIBILITY).read())
    }
}
