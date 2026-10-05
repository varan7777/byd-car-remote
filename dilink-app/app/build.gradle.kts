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

android {
    namespace = "ua.zaika.carremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "ua.zaika.carremote"
        minSdk = 29
        // DiLink 4.0 runs Android 10 (API 29). Staying on 29 avoids the newer
        // background-start and foreground-service-type restrictions.
        targetSdk = 29
        versionCode = 40
        versionName = "0.17.2"

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${secrets.getProperty("TELEGRAM_BOT_TOKEN", "")}\"")
        buildConfigField("long", "OWNER_CHAT_ID", "${secrets.getProperty("OWNER_CHAT_ID", "0")}L")
    }

    buildFeatures { buildConfig = true }

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
