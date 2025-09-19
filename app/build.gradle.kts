import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Безвнешнепроцессный способ получить короткий SHA из .git (совместим с config-cache)
fun readGitShortSha(projectDir: File): String {
    return try {
        val headFile = File(projectDir, ".git/HEAD")
        if (!headFile.isFile) return "nogit"
        val head = headFile.readText().trim()
        val refPrefix = "ref: "
        val fullSha = if (head.startsWith(refPrefix)) {
            val ref = head.removePrefix(refPrefix).trim()
            val refFile = File(projectDir, ".git/$ref")
            if (refFile.isFile) refFile.readText().trim() else "nogit"
        } else {
            // detached HEAD — в HEAD уже полный SHA
            head
        }
        if (fullSha.length >= 7) fullSha.take(7) else fullSha
    } catch (_: Exception) {
        "nogit"
    }
}

// Гит-SHA и время сборки (UTC). Безопасно работают и вне git (вернётся "local").
val gitSha: String = readGitShortSha(rootProject.projectDir)
//val buildTimeUtc: String = Instant.now().toString()
val buildTimeUtc: String = ZonedDateTime.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))

android {
    namespace = "com.buligin.vishnucast"
    compileSdk = 36


    val versionMajor = 1
    val versionMinor = 3

    // val versionPatch = 0  // ← больше не нужен, можно вернуть при необходимости
    // versionName = "3.6"
    val verName = "$versionMajor.$versionMinor"
    // versionCode = 306  (3*100 + 6)
    val verCode = versionMajor * 100 + versionMinor

    defaultConfig {
        applicationId = "com.buligin.vishnucast"
        minSdk = 24
        targetSdk = 34

        versionCode = verCode
        versionName = verName

        // Поля для экрана "About"
        buildConfigField("String", "BUILD_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeUtc\"")
        buildConfigField("String", "APP_VERSION", "\"$verName\"")

        defaultConfig {
            vectorDrawables { useSupportLibrary = true }
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
    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    // Встроенный HTTP+WS сервер
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // QR-коды
    implementation("com.google.zxing:core:3.5.3")
    // WebRTC
    implementation("io.github.webrtc-sdk:android:137.7151.03")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
