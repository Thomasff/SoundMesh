// The Windows client, and for now only the question of whether it can exist: does this build
// produce a module that runs on a JDK new enough for the foreign function API, compiles Kotlin
// against it, and puts core on its classpath unchanged?
//
// core is built for 17 and stays there - it has to keep running inside the Android app. A newer
// toolchain here consumes that bytecode fine; the arrow only points one way, so the two numbers
// below are deliberately different rather than accidentally so.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    // 25, because java.lang.foreign is preview before 22 and preview does not ship.
    // No toolchain resolver is configured, so this JDK has to already be installed rather than
    // being fetched: a machine without it fails `gradlew build`, though not `:app:assembleDebug`.
    jvmToolchain(25)
}

// Kotlin 2.2.10 emits at most JVM 24 bytecode, and its version is pinned to whatever the Android
// plugin brings, so the ceiling is not ours to raise here. Java is brought down to meet it - the
// toolchain above still runs and compiles on 25, and the foreign function API is a runtime one,
// so nothing this module needs lives in the two versions' difference. Without this the build
// fails outright rather than producing anything mismatched.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.soundmesh.desktop.SpikeKt")
    // Without this the JDK prints a warning on the first restricted call and threatens to make
    // it an error in a later release. Naming the module keeps that promise from becoming ours.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
