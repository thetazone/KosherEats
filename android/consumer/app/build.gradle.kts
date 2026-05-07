import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase config from local.properties — see FIREBASE.md. Blank defaults
// mean the app still builds without Firebase set up; PushBootstrap skips
// init gracefully at runtime.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun lp(key: String): String = localProps.getProperty(key, "")

android {
    namespace = "com.koshereats.consumer"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val ksPassword = findProperty("KEYSTORE_PASSWORD")?.toString() ?: lp("KEYSTORE_PASSWORD")
            val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

            if (ksPassword.isEmpty() && isReleaseBuild) {
                error("KEYSTORE_PASSWORD not set — add it to local.properties or pass via -P")
            }

            storeFile = file("release-upload.jks")
            storePassword = ksPassword.ifEmpty { "placeholder" }
            keyAlias = "upload"
            keyPassword = ksPassword.ifEmpty { "placeholder" }
        }
    }

    defaultConfig {
        applicationId = "com.koshereats.consumer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BASE_URL", "\"https://koshereats-api.fly.dev/api/v1/\"")

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${lp("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_APP_ID",     "\"${lp("FIREBASE_CONSUMER_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY",    "\"${lp("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID",  "\"${lp("FIREBASE_SENDER_ID")}\"")

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${lp("GOOGLE_WEB_CLIENT_ID")}\"")

        // Placeholder is empty in dev — real key goes in via Gradle -P or local.properties before launch.
        manifestPlaceholders["MAPS_API_KEY"] = lp("MAPS_API_KEY")
    }

    buildTypes {
        debug {
            // Swap to "https://koshereats-api.fly.dev/api/v1/" when testing Stripe
            // PaymentSheet — real test-mode keys live on Fly, not the local dev backend.
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
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

    // Google Maps
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Firebase: FCM + Crashlytics + Analytics. google-services plugin
    // auto-initializes the default FirebaseApp from app/google-services.json,
    // so PushBootstrap.init now no-ops on the already-present default app.
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Stripe PaymentSheet — keys come from server via /payments/intent.
    // Test-mode keys are live in dev; production keys are swapped in on Fly.
    implementation("com.stripe:stripe-android:20.45.0")

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
}

