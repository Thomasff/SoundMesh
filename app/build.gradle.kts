plugins {
    id("com.android.application")
}

android {
    namespace = "com.soundmesh.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundmesh.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    testImplementation("junit:junit:4.13.2")
}
