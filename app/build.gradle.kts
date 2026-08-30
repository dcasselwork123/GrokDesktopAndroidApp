plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.grokdesktop.quest"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.grokdesktop.quest"
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-spike"
        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

val syncSpikeAssets by tasks.registering(Copy::class) {
    from(rootProject.file("overlay/server/questEntry.js"))
    into(layout.projectDirectory.dir("src/main/assets/grok-desktop/server"))
}

tasks.named("preBuild") {
    dependsOn(syncSpikeAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
