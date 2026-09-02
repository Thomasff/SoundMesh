package com.soundmesh.probe

import java.util.concurrent.locks.ReentrantLock

/** Immutable snapshot of one delayed replay buffer. */
data class DelayedPcmStats(
    val queuedFrames: Int,
    val maxQueuedFrames: Int,
    val droppedFrames: Long,
    val underflows: Long
)

/**
 * Bounded frame-aligned FIFO between the capture thread and the local player.
 * It withholds audio until the configured delay is buffered, never blocks the
 * writer, and drops the oldest whole frames when the bound is reached.
 */
class DelayedPcmBuffer(
    private val bytesPerFrame: Int,
    private val delayBytes: Int,
    private val capacityBytes: Int
) {
    init {
        require(bytesPerFrame > 0) { "bytesPerFrame must be positive" }
        require(delayBytes >= 0 && delayBytes % bytesPerFrame == 0) { "delayBytes must be a whole number of frames" }
        require(capacityBytes > 0 && capacityBytes % bytesPerFrame == 0) { "capacityBytes must be a whole number of frames" }
        require(capacityBytes >= delayBytes) { "capacityBytes must be able to hold the configured delay" }
    }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val ring = ByteArray(capacityBytes)
    private var head = 0
    private var size = 0
    private var released = false
    private var closed = false
    private var maxQueued = 0
    private var droppedFrames = 0L
    private var underflows = 0L

    fun write(bytes: ByteArray, length: Int) {
        require(length >= 0 && length % bytesPerFrame == 0) { "length must be a whole number of frames" }
        lock.lock()
        try {
            if (closed) return
            var offset = 0
            var remaining = length
            if (remaining > capacityBytes) {
                // Keep only the newest capacity worth of audio from an oversized chunk.
                val skipped = remaining - capacityBytes
                discardOldest(0, skipped)
                offset += skipped
                remaining = capacityBytes
            }
            val overflow = size + remaining - capacityBytes
            if (overflow > 0) discardOldest(overflow, 0)
            while (remaining > 0) {
                val tail = (head + size) % capacityBytes
                val run = minOf(remaining, capacityBytes - tail)
                System.arraycopy(bytes, offset, ring, tail, run)
                size += run
                offset += run
                remaining -= run
            }
            if (size > maxQueued) maxQueued = size
            if (size >= delayBytes) released = true
            available.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun read(target: ByteArray, timeoutNanos: Long): Int {
        lock.lock()
        try {
            var remainingWait = timeoutNanos
            while (true) {
                if (released || closed) {
                    val aligned = minOf(size, target.size - target.size % bytesPerFrame)
                    if (aligned > 0) return copyOut(target, aligned)
                    if (closed) return END_OF_STREAM
                }
                if (remainingWait <= 0) {
                    if (released) underflows++
                    return 0
                }
                remainingWait = available.awaitNanos(remainingWait)
            }
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.lock()
        try {
            closed = true
            available.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun stats(): DelayedPcmStats {
        lock.lock()
        try {
            return DelayedPcmStats(size / bytesPerFrame, maxQueued / bytesPerFrame, droppedFrames, underflows)
        } finally {
            lock.unlock()
        }
    }

    private fun copyOut(target: ByteArray, aligned: Int): Int {
        var offset = 0
        var remaining = aligned
        while (remaining > 0) {
            val run = minOf(remaining, capacityBytes - head)
            System.arraycopy(ring, head, target, offset, run)
            head = (head + run) % capacityBytes
            size -= run
            offset += run
            remaining -= run
        }
        return aligned
    }

    private fun discardOldest(queuedBytes: Int, unwrittenBytes: Int) {
        if (queuedBytes > 0) {
            head = (head + queuedBytes) % capacityBytes
            size -= queuedBytes
        }
        droppedFrames += ((queuedBytes + unwrittenBytes) / bytesPerFrame).toLong()
    }

    companion object {
        const val END_OF_STREAM = -1
    }
}
