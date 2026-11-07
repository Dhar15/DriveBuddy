import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.drivebuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.drivebuddy"
        minSdk = 29          // OnePlus Nord 5 runs Android 15; 29 keeps foreground-service type APIs simple
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The library ships native code for four CPU types; the Nord 5 only needs arm64.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        buildConfigField("String", "MAPS_API_KEY", "\"${localProps.getProperty("MAPS_API_KEY", "")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${localProps.getProperty("GROQ_API_KEY", "")}\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Deliberately no AndroidX / Compose in milestone 1: fewer moving parts to break the first build.
    testImplementation("junit:junit:4.13.2")
}
