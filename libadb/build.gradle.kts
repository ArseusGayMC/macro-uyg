plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.annotation)
    implementation(libs.bcprov.jdk15to18)
    implementation(libs.spake2.android)
    testImplementation(libs.junit)
}
