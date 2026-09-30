plugins {
    id("com.android.application") version "8.13.0"
    kotlin("android") version "2.3.0"
}

android {
    namespace = "dev.natromacro"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.natromacro"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }

    buildTypes {
        debug {
            // The existing Actions workflow signs this APK afterwards.
            signingConfig = null
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":natro-core"))
}
