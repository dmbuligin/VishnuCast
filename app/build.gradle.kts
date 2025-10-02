import java.io.File
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Без внешних процессов: короткий SHA из .git (совместим с config-cache)
fun readGitShortSha(projectDir: File): String {
    return try {
        val headFile = File(projectDir, ".git/HEAD")
        if (!headFile.isFile) return "nogit"
        val head = headFile.readText().trim()
        val fullSha = if (head.startsWith("ref:")) {
            val refPath = head.removePrefix("ref:").trim()
            val refFile = File(projectDir, ".git/$refPath")
            if (refFile.isFile) refFile.readText().trim() else "nogit"
        } else {
            head // detached HEAD
        }
        if (fullSha.length >= 7) fullSha.take(7) else fullSha
    } catch (_: Exception) {
        "nogit"
    }
}

val gitSha: String = readGitShortSha(rootProject.projectDir)
val buildTimeUtc: String = ZonedDateTime.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))

android {
    namespace = "com.buligin.vishnucast"
    compileSdk = 36

    // Версии приложения
    val versionMajor = 2
    val versionMinor = 0
    val versionPatch = 2
    val verName = "$versionMajor.$versionMinor.$versionPatch"
    val verCode = versionMajor * 10000 + versionMinor * 100 + versionPatch

    defaultConfig {
        applicationId = "com.buligin.vishnucast"
        minSdk = 24
        targetSdk = 34

        versionCode = verCode
        versionName = verName

        // Поля About
        buildConfigField("String", "BUILD_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeUtc\"")
        buildConfigField("String", "APP_VERSION", "\"$verName\"")

        vectorDrawables { useSupportLibrary = true }

        // ABI под vcmix (можно расширить при необходимости)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Подключаем CMake-проект (JNI-каркас vcmix)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev+$gitSha"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {
    // AndroidX / Material
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Встроенный HTTP+WS сервер
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // QR-коды
    implementation("com.google.zxing:core:3.5.3")

    // WebRTC
    implementation("io.github.webrtc-sdk:android:137.7151.03")

    // ExoPlayer
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")

    // Tests (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
}
