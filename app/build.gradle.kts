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
    testImplementation("junit:junit:4.13.2")
}
