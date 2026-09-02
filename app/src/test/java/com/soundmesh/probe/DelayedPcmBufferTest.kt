package com.soundmesh.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedPcmBufferTest {
    private fun buffer(delayBytes: Int = 8, capacityBytes: Int = 16) =
        DelayedPcmBuffer(bytesPerFrame = 4, delayBytes = delayBytes, capacityBytes = capacityBytes)

    private fun frames(vararg values: Int) = ByteArray(values.size * 4) { values[it / 4].toByte() }

    @Test
    fun withholdsAudioUntilTheConfiguredDelayIsBuffered() {
        val buffer = buffer()
        val target = ByteArray(8)

        buffer.write(frames(1), 4)
        assertEquals(0, buffer.read(target, 0))

        buffer.write(frames(2), 4)
        assertEquals(8, buffer.read(target, 0))
    }

    @Test
    fun returnsWholeFramesInWriteOrder() {
        val buffer = buffer()
        buffer.write(frames(1, 2), 8)
        val target = ByteArray(8)

        assertEquals(8, buffer.read(target, 0))
        assertArrayEquals(frames(1, 2), target)
    }

    @Test
    fun neverReturnsAPartialFrame() {
        val buffer = buffer()
        buffer.write(frames(1, 2), 8)
        val target = ByteArray(7)

        assertEquals(4, buffer.read(target, 0))
    }

    @Test
    fun dropsTheOldestFrameWhenCapacityIsExceeded() {
        val buffer = buffer(delayBytes = 4, capacityBytes = 8)
        buffer.write(frames(1, 2), 8)
        buffer.write(frames(3), 4)

        val target = ByteArray(8)
        assertEquals(8, buffer.read(target, 0))
        assertArrayEquals(frames(2, 3), target)
        assertEquals(1, buffer.stats().droppedFrames)
    }

    @Test
    fun countsAnUnderflowInsteadOfBlockingForever() {
        val buffer = buffer(delayBytes = 4, capacityBytes = 8)
        buffer.write(frames(1), 4)
        val target = ByteArray(4)
        assertEquals(4, buffer.read(target, 0))

        val startedAt = System.nanoTime()
        assertEquals(0, buffer.read(target, 20_000_000L))
        assertTrue(System.nanoTime() - startedAt < 5_000_000_000L)
        assertEquals(1, buffer.stats().underflows)
    }

    @Test
    fun drainsRemainingAudioAfterCloseThenReportsEndOfStream() {
        val buffer = buffer(delayBytes = 8, capacityBytes = 16)
        buffer.write(frames(1, 2), 8)
        buffer.close()

        val target = ByteArray(8)
        assertEquals(8, buffer.read(target, 0))
        assertEquals(-1, buffer.read(target, 0))
    }

    @Test
    fun releasesAWaitingReaderOnClose() {
        val buffer = buffer(delayBytes = 8, capacityBytes = 16)
        buffer.close()

        assertEquals(-1, buffer.read(ByteArray(8), 1_000_000_000L))
    }

    @Test
    fun tracksQueuedFramesAndMaximumDepth() {
        val buffer = buffer(delayBytes = 4, capacityBytes = 16)
        buffer.write(frames(1, 2, 3), 12)
        buffer.read(ByteArray(8), 0)
        buffer.write(frames(4), 4)

        val stats = buffer.stats()
        assertEquals(2, stats.queuedFrames)
        assertEquals(3, stats.maxQueuedFrames)
        assertEquals(0, stats.droppedFrames)
    }

    @Test
    fun rejectsConfigurationThatCannotHoldTheDelayInWholeFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            DelayedPcmBuffer(bytesPerFrame = 4, delayBytes = 8, capacityBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DelayedPcmBuffer(bytesPerFrame = 4, delayBytes = 6, capacityBytes = 16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DelayedPcmBuffer(bytesPerFrame = 0, delayBytes = 8, capacityBytes = 16)
        }
    }
}
