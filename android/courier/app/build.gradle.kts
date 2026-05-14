import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    kotlin("kapt")
}

// Load Firebase config from local.properties so real keys never get committed.
// Empty defaults mean the app builds + runs fine without Firebase — FCM just
// won't register a token. Real values get dropped in during FIREBASE.md setup.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun lp(key: String): String = localProps.getProperty(key, "")

android {
    namespace = "com.koshereats.courier"
    compileSdk = 35

    signingConfigs {
        // Shared debug keystore committed at repo root. Pinned SHA-1 so Firebase
        // OAuth client and Google Sign-In keep working across machines / CI.
        // SHA-1: 54:E0:35:60:7C:2F:A2:A1:67:FA:75:B9:50:F8:03:6B:37:2B:45:46
        getByName("debug") {
            storeFile = file("../../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("release-upload.jks")
            storePassword = lp("KEYSTORE_PASSWORD").ifEmpty { "koshereats2026" }
            keyAlias = "upload"
            keyPassword = lp("KEYSTORE_PASSWORD").ifEmpty { "koshereats2026" }
        }
    }

    defaultConfig {
        // applicationId moved into productFlavors; namespace stays kosher-named
        // so R + source packages don't depend on flavor.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BASE_URL", "\"https://koshereats-api.fly.dev/api/v1/\"")

        // Placeholder is empty in dev — real key goes in via Gradle -P or local.properties before launch.
        manifestPlaceholders["MAPS_API_KEY"] = lp("MAPS_API_KEY")
        // Expose the same key to runtime code (Directions HTTP API) so the map
        // screen doesn't have to re-read it from the manifest at runtime.
        buildConfigField("String", "MAPS_API_KEY", "\"${lp("MAPS_API_KEY")}\"")
    }

    flavorDimensions += "brand"
    productFlavors {
        create("koshereats") {
            dimension = "brand"
            applicationId = "com.koshereats.courier"
            buildConfigField("String", "BRAND", "\"koshereats\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${lp("FIREBASE_PROJECT_ID")}\"")
            buildConfigField("String", "FIREBASE_APP_ID",     "\"${lp("FIREBASE_COURIER_APP_ID")}\"")
            buildConfigField("String", "FIREBASE_API_KEY",    "\"${lp("FIREBASE_API_KEY")}\"")
            buildConfigField("String", "FIREBASE_SENDER_ID",  "\"${lp("FIREBASE_SENDER_ID")}\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${lp("GOOGLE_WEB_CLIENT_ID")}\"")
        }
        create("greeneats") {
            dimension = "brand"
            applicationId = "com.greeneats.courier"
            buildConfigField("String", "BRAND", "\"greeneats\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${lp("GREENEATS_FIREBASE_PROJECT_ID")}\"")
            buildConfigField("String", "FIREBASE_APP_ID",     "\"${lp("GREENEATS_FIREBASE_COURIER_APP_ID")}\"")
            buildConfigField("String", "FIREBASE_API_KEY",    "\"${lp("GREENEATS_FIREBASE_API_KEY")}\"")
            buildConfigField("String", "FIREBASE_SENDER_ID",  "\"${lp("GREENEATS_FIREBASE_SENDER_ID")}\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${lp("GREENEATS_GOOGLE_WEB_CLIENT_ID")}\"")
        }
    }

    buildTypes {
        debug {
            // Point at the Fly backend even in debug so the emulator works without
            // a local Postgres + Stripe + FCM setup. Switch to "http://10.0.2.2:8080/api/v1/"
            // (and allow cleartext in network_security_config.xml) only when actively
            // running a local backend.
            buildConfigField("String", "BASE_URL", "\"https://koshereats-api.fly.dev/api/v1/\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "BASE_URL", "\"https://koshereats-api.fly.dev/api/v1/\"")
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Google Maps (couriers see the delivery route on the in-app map).
    // android-maps-utils provides PolyUtil for decoding Directions API polylines.
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    // Credential Manager + Google Identity (Sign in with Google)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Chrome Custom Tabs (Stripe Connect hosted onboarding)
    implementation("androidx.browser:browser:1.7.0")

    // Firebase Cloud Messaging (Android push). Manual FirebaseApp init in
    // PushBootstrap — no google-services plugin, so builds work without a
    // google-services.json file being present. See FIREBASE.md for setup.
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Activity result APIs for image picking
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

kapt {
    correctErrorTypes = true
}
