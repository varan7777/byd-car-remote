import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The bot token and owner chat id live in a gitignored file — never commit them.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val legacyTelegram = providers.gradleProperty("legacyTelegram").orNull == "true"

android {
    namespace = "ua.zaika.carremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "ua.zaika.carremote"
        minSdk = 29
        // DiLink 4.0 runs Android 10 (API 29). Staying on 29 avoids the newer
        // background-start and foreground-service-type restrictions.
        targetSdk = 29
        versionCode = 64
        versionName = "0.22.4"

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${if (legacyTelegram) secrets.getProperty("TELEGRAM_BOT_TOKEN", "") else ""}\"")
        buildConfigField("long", "OWNER_CHAT_ID", "${if (legacyTelegram) secrets.getProperty("OWNER_CHAT_ID", "0") else "0"}L")
    }

    buildFeatures { buildConfig = true }
    // This APK is sideloaded onto DiLink, not distributed through Google Play. API 29 is
    // intentional for the head unit's background-service behaviour.
    lint { disable += "ExpiredTargetSdkVersion" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Raster-tile (OpenStreetMap) slippy map rendered on Canvas — no WebGL, no Google Play.
    // Lets the air-threat map zoom down to street level on head units whose WebView lacks WebGL.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Efficient scrolling list for the (1000+) trip journal.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Pure-Kotlin ADB client (Apache 2.0): lets the app reach the head unit's own adbd.
    implementation("dev.mobile:dadb:1.2.9") {
        // dadb's POM leaks a GraalVM test helper that drags all of JUnit into the APK.
        exclude(group = "org.graalvm.buildtools")
    }
}
