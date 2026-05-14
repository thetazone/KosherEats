import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

// Firebase config comes from local.properties — see FIREBASE.md. Blank
// defaults keep CI / fresh-clone builds green; PushBootstrap skips init
// at runtime when any field is empty.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun lp(key: String): String = localProps.getProperty(key, "")

android {
    namespace = "com.koshereats.seller"
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
        // since R + source packages don't depend on flavor.
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BASE_URL", "\"https://koshereats-api.fly.dev/api/v1/\"")
    }

    flavorDimensions += "brand"
    productFlavors {
        create("koshereats") {
            dimension = "brand"
            applicationId = "com.koshereats.seller"
            buildConfigField("String", "BRAND", "\"koshereats\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${lp("FIREBASE_PROJECT_ID")}\"")
            buildConfigField("String", "FIREBASE_APP_ID",     "\"${lp("FIREBASE_SELLER_APP_ID")}\"")
            buildConfigField("String", "FIREBASE_API_KEY",    "\"${lp("FIREBASE_API_KEY")}\"")
            buildConfigField("String", "FIREBASE_SENDER_ID",  "\"${lp("FIREBASE_SENDER_ID")}\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${lp("GOOGLE_WEB_CLIENT_ID")}\"")
        }
        create("greeneats") {
            dimension = "brand"
            applicationId = "com.greeneats.seller"
            buildConfigField("String", "BRAND", "\"greeneats\"")
            // Placeholders until GreenEats Firebase + OAuth are provisioned.
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${lp("GREENEATS_FIREBASE_PROJECT_ID")}\"")
            buildConfigField("String", "FIREBASE_APP_ID",     "\"${lp("GREENEATS_FIREBASE_SELLER_APP_ID")}\"")
            buildConfigField("String", "FIREBASE_API_KEY",    "\"${lp("GREENEATS_FIREBASE_API_KEY")}\"")
            buildConfigField("String", "FIREBASE_SENDER_ID",  "\"${lp("GREENEATS_FIREBASE_SENDER_ID")}\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${lp("GREENEATS_GOOGLE_WEB_CLIENT_ID")}\"")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Retrofit + Moshi
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.15.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // DataStore for local prefs
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Firebase Cloud Messaging. Manual init (no google-services plugin) —
    // see PushBootstrap + FIREBASE.md.
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // coroutines-play-services for FirebaseMessaging.token.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Google Sign-In via CredentialManager (modern API, Android 14+ friendly)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
