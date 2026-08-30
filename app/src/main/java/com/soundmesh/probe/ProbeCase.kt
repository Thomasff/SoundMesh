package com.soundmesh.probe

import android.content.Intent

enum class ProbeMode {
    CAPTURE_ONLY,
    DELAYED_LOCAL_PLAYBACK
}

/** Validated configuration for one capture probe run. */
data class ProbeCase(
    val caseId: String,
    val durationSeconds: Int,
    val expectedPackage: String,
    val mode: ProbeMode
) {
    init {
        require(isSafeCaseId(caseId)) {
            "caseId must match [A-Z][0-9]+"
        }
        require(durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            "durationSeconds must be between $MIN_DURATION_SECONDS and $MAX_DURATION_SECONDS"
        }
        require(expectedPackage in ALLOWED_EXPECTED_PACKAGES) {
            "expectedPackage is not an allowed playback package"
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CASE_ID = "case_id"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_EXPECTED_PACKAGE = "expected_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FINISH_SESSION = "finish_session"
        const val EXTRA_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_RESULT_DATA = "media_projection_result_data"

        const val MIN_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 120

        val ALLOWED_EXPECTED_PACKAGES: Set<String> = setOf(
            "com.netease.cloudmusic",
            "com.tencent.qqmusic"
        )

        private val CASE_ID_PATTERN = Regex("[A-Z][0-9]+")

        fun isSafeCaseId(caseId: String): Boolean = CASE_ID_PATTERN.matches(caseId)

        fun fromIntent(intent: Intent): ProbeCase {
            val caseId = intent.getStringExtra(EXTRA_CASE_ID)
                ?: throw IllegalArgumentException("missing case_id")
            val durationSeconds = if (intent.hasExtra(EXTRA_DURATION_SECONDS)) {
                intent.getIntExtra(EXTRA_DURATION_SECONDS, -1)
            } else {
                throw IllegalArgumentException("missing duration_seconds")
            }
            val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE)
                ?: throw IllegalArgumentException("missing expected_package")
            val mode = intent.getStringExtra(EXTRA_MODE)?.let { value ->
                try {
                    ProbeMode.valueOf(value)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("unknown mode: $value")
                }
            } ?: ProbeMode.CAPTURE_ONLY
            return ProbeCase(caseId, durationSeconds, expectedPackage, mode)
        }
    }
}
