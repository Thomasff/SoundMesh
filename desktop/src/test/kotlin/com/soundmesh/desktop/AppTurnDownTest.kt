package com.soundmesh.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A volume mixer in memory: which programs have a row, and each row's level. */
internal class FakeMixer : AppMixer {
    val rows = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Float>>()

    fun add(pid: Long, name: String, level: Float) {
        rows[pid] = name to level
    }

    fun level(pid: Long): Float? = rows[pid]?.second

    // Without the system sounds, as the real one lists.
    override fun list(): List<AudioSession> =
        rows.filterKeys { it != AppTurnDown.SYSTEM_SOUNDS }.map { (pid, row) -> AudioSession(pid, row.first, playing = true) }

    override fun volume(pid: Long): Float? = rows[pid]?.second

    override fun setVolume(pid: Long, level: Float): Boolean {
        val row = rows[pid] ?: return false
        rows[pid] = row.first to level
        return true
    }
}

class AppTurnDownTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * Turned down to a thousandth of where it was, with the gain that undoes it, and put back
     * where it was. A thousandth of its own level rather than a fixed one, so every program held
     * down is undone by the same gain - the one a capture of all of them at once can apply.
     */
    @Test
    fun aProgramIsTurnedDownAndPutBack() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.5f) }
        val hold = AppTurnDown(folder.root, mixer)

        val gain = hold.turnDown(7, "chrome")
        assertEquals(0.5f * AppTurnDown.LEVEL, mixer.level(7)!!, 1e-9f)
        assertEquals(1f / AppTurnDown.LEVEL, gain, 0.01f)

        hold.putBack()
        assertEquals(0.5f, mixer.level(7))
        assertEquals(emptyList<String>(), hold.held())
    }

    @Test
    fun severalProgramsAreHeldAtOnce() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.5f); add(8, "music", 0.9f) }
        val hold = AppTurnDown(folder.root, mixer)

        hold.turnDown(7, "chrome")
        hold.turnDown(8, "music")
        assertEquals(0.5f * AppTurnDown.LEVEL, mixer.level(7)!!, 1e-9f)
        assertEquals(0.9f * AppTurnDown.LEVEL, mixer.level(8)!!, 1e-9f)

        hold.putBack()
        assertEquals(0.5f, mixer.level(7))
        assertEquals(0.9f, mixer.level(8))
    }

    /**
     * Asked again for a program it already holds, it does not read the level it left there, nor
     * write it down again - 所有声音 asks four times a second.
     */
    @Test
    fun aProgramHeldAlreadyIsNotTurnedDownTwice() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.5f) }
        val hold = AppTurnDown(folder.root, mixer)

        hold.turnDown(7, "chrome")
        assertEquals(1f / AppTurnDown.LEVEL, hold.turnDown(7, "chrome"), 0.01f)
        assertEquals(0.5f * AppTurnDown.LEVEL, mixer.level(7)!!, 1e-9f)
        assertEquals(listOf("chrome"), hold.held())

        hold.putBack()
        assertEquals(0.5f, mixer.level(7))
    }

    /**
     * A program restarted while it is held comes back where Windows remembers it - turned down -
     * and that is not the level to put back: the one written for its name is.
     */
    @Test
    fun aHeldProgramStartedAgainIsHeldAtTheLevelWrittenForIt() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.8f) }
        val hold = AppTurnDown(folder.root, mixer)
        hold.turnDown(7, "chrome")

        mixer.rows.remove(7)
        mixer.add(9, "chrome", 0.8f * AppTurnDown.LEVEL)
        hold.turnDown(9, "chrome")
        assertEquals(0.8f * AppTurnDown.LEVEL, mixer.level(9)!!, 1e-9f)

        hold.putBack()
        assertEquals(0.8f, mixer.level(9))
    }

    /** The system sounds are not in the mixer's list of programs, and are put back all the same. */
    @Test
    fun theSystemSoundsArePutBack() {
        val mixer = FakeMixer().apply { add(AppTurnDown.SYSTEM_SOUNDS, "", 0.9f) }
        val hold = AppTurnDown(folder.root, mixer)

        hold.turnDown(AppTurnDown.SYSTEM_SOUNDS, "system sounds")
        assertEquals(0.9f * AppTurnDown.LEVEL, mixer.level(AppTurnDown.SYSTEM_SOUNDS)!!, 1e-9f)

        hold.putBack()
        assertEquals(0.9f, mixer.level(AppTurnDown.SYSTEM_SOUNDS))
        assertTrue(hold.held().isEmpty())
    }

    /**
     * A host that died while holding a program down: the next one to open puts it back, even
     * though it knows nothing but what was written down - and, the program having been started
     * again since, not even its process.
     */
    @Test
    fun whatADeadHostLeftDownIsPutBackByTheNext() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.8f) }
        AppTurnDown(folder.root, mixer).turnDown(7, "chrome")

        mixer.rows.remove(7)
        mixer.add(9, "chrome", AppTurnDown.LEVEL)
        AppTurnDown(folder.root, mixer).putBack()
        assertEquals(0.8f, mixer.level(9))
        assertEquals(emptyList<String>(), AppTurnDown(folder.root, mixer).held())
    }

    /** A program that is not running cannot be put back yet, and is not forgotten. */
    @Test
    fun aProgramNotRunningIsKeptUntilItIs() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.8f) }
        val hold = AppTurnDown(folder.root, mixer)
        hold.turnDown(7, "chrome")
        mixer.rows.remove(7)

        hold.putBack()
        assertEquals(listOf("chrome"), hold.held())

        mixer.add(9, "chrome", AppTurnDown.LEVEL)
        hold.putBack()
        assertEquals(0.8f, mixer.level(9))
        assertTrue(hold.held().isEmpty())
    }

    /** A program with no row has nothing to turn down, and nothing is written. */
    @Test
    fun aProgramWithNoRowIsLeftAlone() {
        val hold = AppTurnDown(folder.root, FakeMixer())
        assertEquals(1f, hold.turnDown(7, "gone"), 0f)
        assertTrue(hold.held().isEmpty())
    }
}
