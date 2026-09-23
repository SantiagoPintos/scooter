plugins {
    id("com.android.library")
}

android {
    namespace = "com.velocimetro.scooterlab"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
