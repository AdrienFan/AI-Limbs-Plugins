plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ai.limbs.plugincenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.limbs.plugincenter.system.v139"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.3.9"
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    compileOnly(project(":system-sdk-stubs"))
    compileOnly(platform("androidx.compose:compose-bom:2026.02.01"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.material:material-icons-extended")
    compileOnly("androidx.activity:activity-compose:1.8.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    compileOnly("org.json:json:20250517")
}