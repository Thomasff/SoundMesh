package com.soundmesh.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Several programs' sound as one, and everything this machine plays but SoundMesh. */
class PlayFeedsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A capture that hands over what a test pushes, nothing on its own. */
    private class PushedCapture(val target: CaptureTarget, private val onPcm: (ByteArray, Int) -> Unit) : CaptureHandle {
        @Volatile override var gain = 1f
        @Volatile var started = false
        @Volatile var closed = false

        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
        }

        /** One whole chunk with every sample [value]. */
        fun push(value: Short) {
            val chunk = ByteArray(HostStream.CHUNK_BYTES)
            for (i in chunk.indices step 2) {
                chunk[i] = (value.toInt() and 0xFF).toByte()
                chunk[i + 1] = (value.toInt() shr 8).toByte()
            }
            onPcm(chunk, chunk.size)
        }
    }

    private fun samplesOf(chunk: ByteArray): Set<Int> =
        (chunk.indices step 2).map { (chunk[it].toInt() and 0xFF) or (chunk[it + 1].toInt() shl 8) }.toSet()

    private fun eventually(check: () -> Boolean): Boolean {
        val until = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < until) {
            if (check()) return true
            Thread.sleep(10)
        }
        return check()
    }

    @Test
    fun chunksAreAddedAndHeldAtFullScale() {
        val a = ByteArray(8).also { pcm16(it, 1000, 30000, -30000, 5) }
        val b = ByteArray(8).also { pcm16(it, 234, 10000, -10000, -5) }
        val mixed = mixChunks(listOf(a, b))
        assertEquals(listOf(1234, 32767, -32768, 0), (mixed.indices step 2).map {
            ((mixed[it].toInt() and 0xFF) or (mixed[it + 1].toInt() shl 8)).toShort().toInt()
        })
    }

    private fun pcm16(into: ByteArray, vararg values: Int) {
        values.forEachIndexed { i, v ->
            into[i * 2] = (v and 0xFF).toByte()
            into[i * 2 + 1] = (v shr 8).toByte()
        }
    }

    /** Two programs: each captured on its own and turned down, and the room sent the two added. */
    @Test
    fun severalProgramsAreCapturedAndAdded() {
        val mixer = FakeMixer().apply { add(7, "music", 0.5f); add(8, "video", 1f) }
        val captures = mutableListOf<PushedCapture>()
        val feed = AppFeed(
            listOf(AudioSession(7, "music", true), AudioSession(8, "video", true)),
            "music、video",
            { target, onPcm -> PushedCapture(target, onPcm).also { captures.add(it) } },
            AppTurnDown(folder.root, mixer)
        )
        assertNull(feed.prepare())
        assertEquals(listOf(CaptureTarget(7), CaptureTarget(8)), captures.map { it.target })
        assertTrue(captures.all { it.started && it.gain == 1f / AppTurnDown.LEVEL })
        assertEquals(0.5f * AppTurnDown.LEVEL, mixer.level(7)!!, 1e-9f)
        assertEquals(AppTurnDown.LEVEL, mixer.level(8)!!, 1e-9f)

        captures[0].push(1000)
        captures[1].push(234)
        assertEquals(setOf(1234), samplesOf(feed.nextChunk()))

        feed.close()
        assertTrue(captures.all { it.closed })
        assertEquals(0.5f, mixer.level(7))
        assertEquals(1f, mixer.level(8))
    }

    /** One program that cannot be captured: nothing is left turned down, the others included. */
    @Test
    fun aProgramThatCannotBeCapturedLeavesNothingDown() {
        val mixer = FakeMixer().apply { add(7, "music", 0.5f); add(8, "gone", 1f) }
        val captures = mutableListOf<PushedCapture>()
        val feed = AppFeed(
            listOf(AudioSession(7, "music", true), AudioSession(8, "gone", true)),
            "music、gone",
            { target, onPcm ->
                if (target.pid == 8L) error("no such process")
                PushedCapture(target, onPcm).also { captures.add(it) }
            },
            AppTurnDown(folder.root, mixer)
        )
        val problem = feed.prepare()
        assertTrue(problem is HostProblem.CaptureFailed && problem.app == "gone")
        assertTrue(captures.all { it.closed })
        assertEquals(0.5f, mixer.level(7))
        assertEquals(1f, mixer.level(8))
    }

    /**
     * 所有声音: one capture of everything but this process, every row in the mixer turned down -
     * the system sounds too, and a program that starts after - and everything put back at the end.
     */
    @Test
    fun everythingIsCapturedAndEveryRowTurnedDownTheNewOnesToo() {
        val mixer = FakeMixer().apply {
            add(7, "music", 0.5f)
            add(AppTurnDown.SYSTEM_SOUNDS, "", 0.9f)
        }
        val captures = mutableListOf<PushedCapture>()
        val feed = EverythingFeed(
            "所有声音",
            ownPid = 99,
            openCapture = { target, onPcm -> PushedCapture(target, onPcm).also { captures.add(it) } },
            turnDown = AppTurnDown(folder.root, mixer),
            mixer = mixer,
            rescanMillis = 20
        )
        assertNull(feed.prepare())
        assertEquals(listOf(CaptureTarget(99, exclude = true)), captures.map { it.target })
        assertEquals(1f / AppTurnDown.LEVEL, captures.single().gain, 0.01f)
        assertEquals(0.5f * AppTurnDown.LEVEL, mixer.level(7)!!, 1e-9f)
        assertEquals(0.9f * AppTurnDown.LEVEL, mixer.level(AppTurnDown.SYSTEM_SOUNDS)!!, 1e-9f)

        mixer.add(8, "later", 0.7f)
        assertTrue("a program started later was never turned down", eventually {
            mixer.level(8)!! < 0.7f * AppTurnDown.LEVEL * 1.01f
        })

        feed.close()
        assertTrue(captures.single().closed)
        assertEquals(0.5f, mixer.level(7))
        assertEquals(0.7f, mixer.level(8))
        assertEquals(0.9f, mixer.level(AppTurnDown.SYSTEM_SOUNDS))
        // Nothing is turned down after the end, whatever starts then.
        mixer.add(10, "after", 0.6f)
        Thread.sleep(100)
        assertEquals(0.6f, mixer.level(10))
    }

    /**
     * A thousand times a program turned down to a thousandth is the program as it was; a thousand
     * times a row nobody turned down yet - a program that has just started - is sixty decibels
     * over the top, and is not sent.
     */
    @Test
    fun onlyARowNobodyTurnedDownIsTooLoud() {
        val gain = 1f / AppTurnDown.LEVEL
        assertFalse(AppCapture.overloads(1f * AppTurnDown.LEVEL, gain))
        assertFalse(AppCapture.overloads(2f * AppTurnDown.LEVEL, gain))
        assertTrue(AppCapture.overloads(0.5f, gain))
        assertFalse("a capture nothing was turned down for", AppCapture.overloads(1f, 1f))
    }
}
