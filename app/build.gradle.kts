plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every CI build gets a higher
// versionCode and installs cleanly over the previous one.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.nate.scoreline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chickenmybobbers.scoreline"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Release signing key: keystore/release.p12, encrypted with the SIGNING_PASSWORD
    // GitHub secret. The keystore file is useless without that password, which lives only in
    // GitHub's secret settings (never in the code). CI creates the key on first run.
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.p12")
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "scoreology"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!signingPassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Personal sideloaded app: don't let a lint finding fail the CI build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
