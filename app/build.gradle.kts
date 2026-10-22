plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bubu.cast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bubu.cast"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Hỗ trợ 32-bit cho máy chiếu Q10 (armeabi-v7a)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug build cho kiểm thử
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
        compose = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Jetpack Compose (BOM)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media3 ExoPlayer — phát MP4/HLS/WebM
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // WebView
    implementation("androidx.webkit:webkit:1.10.0")

    // DataStore — cài đặt
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room — lịch sử, dấu trang
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DLNA/UPnP — Cling (tìm MediaRenderer trong LAN)
    implementation("org.fourthline.cling:cling-core:2.1.1")
    implementation("org.fourthline.cling:cling-support:2.1.1")

    // Google Cast SDK (tùy chọn — wrap try-catch nếu thiếu GMS)
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
}
