// A plain JVM library, not an Android one. Nothing in core touches Android, and building it
// this way turns that from a claim into a constraint: an Android import now fails to compile
// instead of having to be caught by a grep that only knows the spellings it was given.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
