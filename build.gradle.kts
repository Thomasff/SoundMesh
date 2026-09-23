plugins {
    id("com.android.application") version "9.0.1" apply false
    // Pinned to the Kotlin the Android plugin brings with it. A mismatch here is a compiler
    // crash rather than a version warning.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    // The desktop window. Paired with the Kotlin above by JetBrains' own table: 1.9.0 is the
    // release built against 2.2.x, verified on JDK 25 on 09-23 - see cross-platform.md.
    id("org.jetbrains.compose") version "1.9.0" apply false
}
