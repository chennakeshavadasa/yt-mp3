plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nithin.ytmp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nithin.ytmp3"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1   // = GitHub build number
        versionName = "build-$versionCode"

    }

    // Fixed key so every new build installs over the previous one.
    // Keep this repository PRIVATE.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "ytmp3pass"
            keyAlias = "ytmp3"
            keyPassword = "ytmp3pass"
        }
    }

    buildTypes {
        release {
            // Shrink unused code/resources (smaller APK, faster start)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Required by youtubedl-android (bundled python / ffmpeg binaries)
    buildFeatures {
        buildConfig = true
    }

    // One APK per phone type instead of one big APK with both engines.
    // The in-app updater downloads only the one this phone needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
