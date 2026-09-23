// The window over the desktop sessions, and nothing else.
//
// A module of its own rather than a source set in :desktop, because the two cannot share a
// classpath the application plugin assembles: Compose's runtime and AndroidX's each ship a jar
// named runtime-desktop-1.9.0.jar with different contents, and telling installDist to exclude the
// duplicate builds green and dies at launch on NoClassDefFoundError - it drops the one that was
// needed. So :desktop keeps installDist for the measuring tools and this one runs and packages
// the Compose way.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    // For the same reason as :desktop - the sessions under this window call Windows through FFM.
    jvmToolchain(25)
}

// As in :desktop: Kotlin 2.2.10 emits at most 24, so Java is brought down to meet it.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

dependencies {
    implementation(project(":desktop"))
    // :desktop depends on :core as implementation, so its types (DiscoveryFailure, used in
    // SinkStatus) do not reach this module's classpath without a direct dependency here too.
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    // Not part of currentOs, unlike what its name suggests.
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.soundmesh.desktop.app.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        // Without this, run/package fork on whatever JDK Gradle itself is running on (JAVA_HOME,
        // here 17), not on the jvmToolchain(25) above - so pull the same toolchain JDK explicitly.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            packageName = "SoundMesh"
            packageVersion = "1.0.0"
        }
    }
}
