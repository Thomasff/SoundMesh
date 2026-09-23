package com.soundmesh.probe

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns private per-case status and capture artifacts under Context.filesDir. */
class RunStore(private val filesDir: File) {
    init {
        require(filesDir.isDirectory || filesDir.mkdirs()) {
            "filesDir is not a writable directory"
        }
    }

    @Synchronized
    fun prepareRun(caseId: String): File {
        requireSafeCaseId(caseId)
        val directory = File(filesDir, "runs/$caseId")
        require(directory.isDirectory || directory.mkdirs()) {
            "unable to create private run directory"
        }
        return directory
    }

    fun statusFile(caseId: String): File = artifactFile(caseId, "status.json")

    fun captureWavFile(caseId: String): File = artifactFile(caseId, "capture.wav")

    fun captureJsonFile(caseId: String): File = artifactFile(caseId, "capture.json")

    fun replayFile(caseId: String): File = artifactFile(caseId, "replay.json")

    fun clockFile(caseId: String): File = artifactFile(caseId, "clock.json")

    fun syncFile(caseId: String): File = artifactFile(caseId, "sync.json")

    @Synchronized
    fun writeStatus(caseId: String, statusJson: String) {
        writeAtomically(caseId, "status.json", statusJson)
    }

    @Synchronized
    fun writeCaptureJson(caseId: String, captureJson: String) {
        writeAtomically(caseId, "capture.json", captureJson)
    }

    @Synchronized
    fun writeReplayJson(caseId: String, replayJson: String) {
        writeAtomically(caseId, "replay.json", replayJson)
    }

    @Synchronized
    fun writeClockJson(caseId: String, clockJson: String) {
        writeAtomically(caseId, "clock.json", clockJson)
    }

    @Synchronized
    fun writeSyncJson(caseId: String, syncJson: String) {
        writeAtomically(caseId, "sync.json", syncJson)
    }

    private fun writeAtomically(caseId: String, fileName: String, content: String) {
        val directory = prepareRun(caseId)
        val target = File(directory, fileName)
        val temporary = File.createTempFile("$fileName.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun artifactFile(caseId: String, fileName: String): File {
        val directory = prepareRun(caseId)
        return File(directory, fileName)
    }

    private fun requireSafeCaseId(caseId: String) {
        require(isSafeCaseId(caseId)) {
            "unsafe caseId"
        }
    }

    companion object {
        private val CASE_ID_PATTERN = Regex("[A-Z][0-9]+")

        /** Whether [caseId] can name a directory here. ProbeCase asks the same thing of an intent. */
        fun isSafeCaseId(caseId: String): Boolean = CASE_ID_PATTERN.matches(caseId)
    }
}

object RunStatus {
    fun json(state: String, failureCode: String? = null): String =
        "{\"state\":\"$state\",\"failureCode\":${failureCode?.let { "\"$it\"" } ?: "null"}}"
}
