plugins {
    id("com.android.application")
}

android {
    namespace = "com.velocimetro.scooterlabapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.velocimetro.scooterlabapp"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":scooterlab"))
}
