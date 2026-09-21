plugins {
    id("com.android.application") version "9.0.1" apply false
    // Pinned to the Kotlin the Android plugin brings with it. A mismatch here is a compiler
    // crash rather than a version warning.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}
