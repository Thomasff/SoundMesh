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

dependencies {
    implementation(project(":core"))
    // Decoding is the scanner's, but the writer lives in the same artifact and it is pure JVM, so the
    // code a host shows can be round tripped in a unit test instead of only off a screen.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
