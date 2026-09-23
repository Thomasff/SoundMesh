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

    override fun list(): List<AudioSession> = rows.map { (pid, row) -> AudioSession(pid, row.first, playing = true) }

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

    /** Turned down to a thousandth, with the gain that undoes it, and put back where it was. */
    @Test
    fun aProgramIsTurnedDownAndPutBack() {
        val mixer = FakeMixer().apply { add(7, "chrome", 0.5f) }
        val hold = AppTurnDown(folder.root, mixer)

        val gain = hold.turnDown(7, "chrome")
        assertEquals(AppTurnDown.LEVEL, mixer.level(7))
        assertEquals(500f, gain, 0.01f)

        hold.putBack()
        assertEquals(0.5f, mixer.level(7))
        assertEquals(emptyList<String>(), hold.held())
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
