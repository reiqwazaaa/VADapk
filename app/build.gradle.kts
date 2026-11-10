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
        // Номер сборки CI растёт сам, чтобы каждый следующий APK ставился поверх прошлого
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    // Одна и та же подпись у всех сборок - иначе Android пишет "конфликт пакетов" при обновлении
    signingConfigs {
        create("vad") {
            storeFile = file("vad-debug.keystore")
            storePassword = "android"
            keyAlias = "vad"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("vad")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vad")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Распознавание речи: whisper.cpp (клонируется в CI в app/src/main/cpp/whisper.cpp), только arm64
    ndkVersion = "26.3.11579264"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Нужно, чтобы python/ffmpeg из библиотеки распаковались на диск
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/versions/**")
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")

    // Читалка: markdown, RAR (cbr), 7z (cb7), tar (cbt)
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    // Перевод текста на устройстве (модели языков скачиваются при первом использовании)
    implementation("com.google.mlkit:translate:17.0.3")
}
