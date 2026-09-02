package com.soundmesh.probe

/** Persists a rejected service request so the PC reads a reason instead of polling until it times out. */
object ServiceRejection {
    private const val MAX_FAILURE_CODE_LENGTH = 120
    private val UNSAFE_CHARACTERS = Regex("[^A-Za-z0-9 ._-]")

    fun failureCode(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return raw.replace(UNSAFE_CHARACTERS, "_")
            .take(MAX_FAILURE_CODE_LENGTH)
            .ifEmpty { "INVALID_REQUEST" }
    }

    fun record(runStore: RunStore, caseId: String?, error: Throwable): String? {
        val safeCaseId = caseId?.takeIf { ProbeCase.isSafeCaseId(it) } ?: return null
        runStore.writeStatus(safeCaseId, RunStatus.json(CaptureForegroundService.STATUS_FAILED, failureCode(error)))
        return safeCaseId
    }
}
