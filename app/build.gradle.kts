plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Which commit this build came from, read straight out of .git rather than by running git, so a
// machine without it on PATH still builds. It exists because "which build is on that handset" has
// cost this project three rounds of guessing - twice in one evening on 2026-09-14, once by trusting
// a recollection that turned out to name the wrong phone. A debug build has no version to tell
// them apart by, so it needs one.
val buildMark: String = runCatching {
    val gitDir = rootProject.file(".git")
    val head = File(gitDir, "HEAD").readText().trim()
    if (head.startsWith("ref:")) File(gitDir, head.removePrefix("ref:").trim()).readText().trim().take(7)
    else head.take(7)
}.getOrDefault("unknown")

// What this build calls itself, taken from the tag that started it: v0.2.0 arrives here as
// 0.2.0. The tag is the one place a version is written down - a number kept here as well would
// be a second place, and the two would disagree the first time somebody tagged in a hurry.
val released: String? =
    System.getenv("SOUNDMESH_VERSION")?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }

/**
 * Android compares versionCode and nothing else when deciding whether a package is an upgrade,
 * so it has to grow with the name rather than be maintained next to it. Two digits each for
 * minor and patch, which is how far this scheme goes before it wants replacing.
 */
fun versionCodeOf(name: String): Int {
    val part = { i: Int -> name.split('.').getOrNull(i)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0 }
    return part(0) * 10_000 + part(1) * 100 + part(2)
}

// The release key, from the environment and never from a file in the tree. There is deliberately
// no fallback to the debug key: an unsigned package refuses to install, which is loud, whereas a
// debug-signed one installs perfectly and then can never be upgraded by the real thing, because
// the debug key on a fresh runner is a different key every time.
val releaseKey: File? =
    System.getenv("SOUNDMESH_KEYSTORE")?.let(::File)?.takeIf { it.isFile }

android {
    namespace = "com.soundmesh.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundmesh.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = released?.let(::versionCodeOf) ?: 1
        versionName = released ?: "0.1.0"
        buildConfigField("String", "BUILD_MARK", "\"$buildMark\"")

        // The facts about the project itself, each read from gradle.properties. One left unset
        // arrives here as an empty string, and every screen treats empty as "do not render this
        // at all" - a QR code that scans to nothing is worse than no QR code. The release page is
        // the one still unset.
        for (name in listOf("AUTHOR", "AUTHOR_URL", "REPO_URL", "LICENCE", "RELEASE_URL")) {
            val property = "soundmesh." + name.lowercase().split("_")
                .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }
                .joinToString("")
            buildConfigField("String", name, "\"${project.findProperty(property) ?: ""}\"")
        }
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = System.getenv("SOUNDMESH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SOUNDMESH_KEY_ALIAS")
            keyPassword = System.getenv("SOUNDMESH_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            // Null where no key was handed in, which is every build on a developer's machine.
            signingConfig = signingConfigs.findByName("release")
            // Off until somebody has listened to a minified build all the way through. R8
            // removing something Compose only reaches for at run time is a crash on a phone and
            // a green build here, which is the worst pair of outcomes to have.
            isMinifyEnabled = false
        }
    }

    // The look both windows share - colours, the thin controls, the volume line - kept once in
    // ui-shared and compiled here and into :desktop-app alike, each against its own Compose. A
    // folder rather than a module: a Compose Multiplatform module would bring this app from
    // Compose 1.7 to the desktop's 1.9 along with it. What it costs is that only what both
    // versions have can be written there, and a build of either side says so at once.
    sourceSets {
        getByName("main") {
            kotlin.srcDir(rootProject.file("ui-shared/src"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// One version for the whole CameraX group: they are released together and mixing them is a
// runtime failure rather than a build one.
val cameraX = "1.4.2"

dependencies {
    implementation(project(":core"))
    // Both directions of the code live in the same artifact, and it is pure JVM, so the code a host
    // draws and the frame a sink reads can both be exercised in a unit test instead of only off a
    // screen and a camera.
    implementation("com.google.zxing:core:3.5.3")
    // The scanner, and the first AndroidX in this module. CameraX binds a camera to a lifecycle,
    // which is why ScanActivity is a ComponentActivity while everything else here is a plain
    // Activity - the harness activities have no use for one and are left alone.
    implementation("androidx.activity:activity:1.9.3")
    // Was already here by way of activity; declared because the product now asks it for the
    // system bars' icon colour, which the app picks rather than the system (see SoundMeshTheme).
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // The product's own interface. The harness screens stay on plain Views on purpose: ADB reads
    // them off a screen, and rewriting them would change what the ruler is read with.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
