plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версия библиотеки с yt-dlp + python + ffmpeg для Android.
// Если сборка пишет, что такой версии нет - поставьте последнюю с
// https://github.com/yausername/youtubedl-android/releases
val ytdlVersion = "0.17.2"

android {
    namespace = "com.vad.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vad.downloader"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    // Нужно, чтобы python/ffmpeg из библиотеки распаковались на диск
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")
}
