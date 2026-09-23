package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.Crossover
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialLayout
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SpatialPosition
import com.soundmesh.probe.sync.spatialShaped
import com.soundmesh.product.RoomIcon
import com.soundmesh.product.RoomState
import com.soundmesh.product.ruleOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handset host's room, heard on this machine: the rule it sends down the spatial channel shapes
 * every chunk here the way it shapes them on a handset, and how far away this machine was drawn
 * holds its sound back until the furthest one's has had time to arrive. Until 09-23 the rule
 * was received and dropped, so dragging this machine's icon changed nothing it played.
 */
class SinkSpatialTest {
    private val desk = "desk0123456789ab"

    /**
     * A sink draws its host's room from the rule alone, and the drawing comes back where the host
     * put everybody - the rule's layout undone, not a second arrangement of the same devices.
     */
    @Test
    fun aSinkDrawsTheRoomWhereTheHostPutEverybody() {
        val drawn = listOf(RoomIcon("host0123456789ab", 0.3f, 0.2f), RoomIcon(desk, 0.75f, 0.6f))
        val rule = ruleOf(RoomState(icons = drawn, mode = SpatialMode.SPLIT))!!
        val seen = drawnRoomOf(rule, mapOf(desk to 3), desk)
        assertEquals(drawn.map { it.peerId }, seen.icons.map { it.peerId })
        for ((was, now) in drawn.zip(seen.icons)) {
            assertEquals(was.x, now.x, 1e-6f)
            assertEquals(was.y, now.y, 1e-6f)
        }
        assertEquals(SpatialMode.SPLIT, seen.mode)
        assertEquals(3, seen.colours[desk])
    }
    private val phone = "phone0123456789a"

    /** This machine nearer the listener than the phone, and to one side of it. */
    private val drawn = SpatialField(
        SpatialMode.PAN,
        SpatialLayout(listOf(SpatialPosition(phone, -1.4, 0.9), SpatialPosition(desk, 0.2, 0.3))),
        pan = 0.3,
        metresPerUnit = 2.0
    )

    private fun steady(level: Int = 9_000): ByteArray {
        val pcm = ByteArray(ChunkCodec.FRAMES_PER_CHUNK * 2 * 2)
        for (index in 0 until pcm.size / 2) {
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    private val chunkNanos = ChunkCodec.FRAMES_PER_CHUNK * 1_000_000_000L / 48_000

    @Test
    fun aChunkUnderNoRuleIsPlayedAsItCame() {
        val chunk = AudioChunk(3, 5_000_000_000L, steady())
        assertSame(chunk, SinkSpatial(desk).shaped(chunk))
    }

    /** The same bytes a handset named [desk] would play, which is what makes it one room. */
    @Test
    fun aRuleIsHeardHereAsAHandsetHearsIt() {
        val spatial = SinkSpatial(desk)
        spatial.apply(drawn)
        val first = AudioChunk(3, 5_000_000_000L, steady())
        val second = AudioChunk(4, 5_000_000_000L + chunkNanos, steady())
        val crossover = Crossover()
        val heardFirst = spatial.shaped(first).pcm
        val heardSecond = spatial.shaped(second).pcm

        assertFalse("the rule did nothing", heardFirst.contentEquals(first.pcm))
        assertArrayEquals(spatialShaped(3, first.playAtHostNanos, first.pcm, drawn, desk, crossover = crossover), heardFirst)
        // The second ramps on from where the first ended rather than from where no rule was.
        assertArrayEquals(
            spatialShaped(4, second.playAtHostNanos, second.pcm, drawn, desk, wasUnder = drawn, crossover = crossover),
            heardSecond
        )
        assertEquals(2, spatial.shapedChunks)
    }

    @Test
    fun aMachineDrawnNearerWaitsForTheFurthestOne() {
        val spatial = SinkSpatial(desk)
        spatial.apply(drawn)
        val delay = drawn.arrivalDelayNanosFor(desk)
        assertTrue("the drawing has nobody further away than this machine: $delay", delay > 0L)

        val chunk = AudioChunk(3, 5_000_000_000L, steady())
        assertEquals(chunk.playAtHostNanos + delay, spatial.shaped(chunk).playAtHostNanos)
    }

    /** A drawing that does not name this machine leaves it playing the plain mix, as on a handset. */
    @Test
    fun aRuleForSomebodyElseLeavesThisMachineAlone() {
        val spatial = SinkSpatial("somebody-else-00")
        spatial.apply(drawn)
        val chunk = AudioChunk(3, 5_000_000_000L, steady())
        assertSame(chunk, spatial.shaped(chunk))
    }
}
