package com.soundmesh.probe

interface PcmReader : AutoCloseable {
    val sampleRate: Int
    val channelCount: Int
    fun start()
    fun read(target: ByteArray): Int
    fun stop()
}

interface PcmSink : AutoCloseable {
    fun write(bytes: ByteArray, length: Int, timestampNanos: Long)
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

data class CaptureRunConfig(
    val reader: PcmReader,
    val sink: PcmSink,
    val durationNanos: Long,
    val clock: MonotonicClock,
    val stopRequested: () -> Boolean = { false },
    val bufferSize: Int = 1_920
)

data class CaptureRunSummary(
    val state: CaptureState,
    val capturedBytes: Long,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val failureCode: String?,
    val readerErrorCode: Int? = null
)

object CaptureRunner {
    private const val MAX_CONSECUTIVE_ZERO_READS = 100

    fun run(config: CaptureRunConfig): CaptureRunSummary {
        require(config.durationNanos >= 0) { "durationNanos must not be negative" }
        require(config.bufferSize > 0) { "bufferSize must be positive" }

        val startedAt = config.clock.nowNanos()
        var capturedBytes = 0L
        var state = CaptureState.COMPLETE
        var failureCode: String? = null
        var readerErrorCode: Int? = null
        var cleanupFailure = false

        try {
            try {
                config.reader.start()
            } catch (_: Throwable) {
                state = CaptureState.FAILED
                failureCode = "READER_EXCEPTION"
            }

            if (failureCode == null) {
                val buffer = ByteArray(config.bufferSize)
                var consecutiveZeroReads = 0
                while (true) {
                    if (config.stopRequested()) {
                        state = CaptureState.CANCELLED
                        failureCode = "CANCELLED"
                        break
                    }
                    if (config.clock.nowNanos() - startedAt >= config.durationNanos) break

                    val timestamp = config.clock.nowNanos()
                    val length = try {
                        config.reader.read(buffer)
                    } catch (_: Throwable) {
                        state = CaptureState.FAILED
                        failureCode = "READER_EXCEPTION"
                        break
                    }
                    when {
                        length < 0 -> {
                            state = CaptureState.FAILED
                            failureCode = "READER_ERROR_$length"
                            readerErrorCode = length
                            break
                        }
                        length == 0 -> {
                            consecutiveZeroReads++
                            if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                                state = CaptureState.FAILED
                                failureCode = "READER_STALLED"
                                break
                            }
                        }
                        else -> {
                            consecutiveZeroReads = 0
                            try {
                                config.sink.write(buffer, length, timestamp)
                                capturedBytes += length
                            } catch (_: Throwable) {
                                state = CaptureState.FAILED
                                failureCode = "SINK_EXCEPTION"
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            cleanupFailure = cleanup(config.reader::stop) || cleanupFailure
            cleanupFailure = cleanup(config.reader::close) || cleanupFailure
            cleanupFailure = cleanup(config.sink::close) || cleanupFailure
        }

        if (failureCode == null && cleanupFailure) {
            state = CaptureState.FAILED
            failureCode = "CLEANUP_EXCEPTION"
        }
        return CaptureRunSummary(
            state, capturedBytes, startedAt, config.clock.nowNanos(), failureCode, readerErrorCode
        )
    }

    private fun cleanup(action: () -> Unit): Boolean = try {
        action()
        false
    } catch (_: Throwable) {
        true
    }
}
