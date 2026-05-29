package com.greeneats.seller.data.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.greeneats.seller.BuildConfig
import com.greeneats.seller.data.models.UnknownFallbackEnumAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "seller_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val RESTAURANT_ID = stringPreferencesKey("restaurant_id")
}

// URL configuration:
// BuildConfig.BASE_URL is set in app/build.gradle.kts — one value per build
// type (debug / release). Both currently point at the production Fly.io
// backend (greeneats-api.fly.dev). To test against a local backend, override
// the debug buildConfigField in build.gradle.kts to "http://10.0.2.2:8080/api/v1/"
// (10.0.2.2 is the emulator's alias for the host machine's localhost).
//
// On iOS the equivalent lives in APIService.swift as a hardcoded baseURL with
// an environment-variable override (KOSHEREATS_API_URL) in DEBUG builds.
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var cachedToken: String? = null
    @Volatile var cachedRefreshToken: String? = null
    @Volatile var cachedRestaurantId: String? = null

    // EncryptedSharedPreferences instance for auth tokens. Initialized lazily
    // in provideOkHttpClient via initTokenPrefs(). Falls back to plain
    // SharedPreferences if the Android Keystore is unavailable.
    @Volatile var tokenPrefs: SharedPreferences? = null

    val sessionExpired = MutableStateFlow(false)

    // Emitted by RestaurantPickerViewModel.select() whenever the active restaurant changes.
    // OrdersViewModel, MenuViewModel, and DealsViewModel collect this to reset + reload.
    val restaurantChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Creates EncryptedSharedPreferences backed by MasterKey / Android Keystore.
     * Falls back to plain SharedPreferences if keystore is unavailable (device
     * restore, OS upgrade, locked keystore) — user will be treated as logged-out
     * and tokens re-populate on next successful login.
     */
    private fun initTokenPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "greeneats_seller_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences("greeneats_seller_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(UnknownFallbackEnumAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient {
        // Initialize encrypted token storage
        val prefs = initTokenPrefs(context)
        tokenPrefs = prefs

        // Prime token caches from encrypted prefs on IO thread
        appScope.launch {
            cachedToken = prefs.getString("auth_token", null)
            cachedRefreshToken = prefs.getString("refresh_token", null)
        }

        // Restaurant ID stays in DataStore — not a secret
        appScope.launch {
            val initialPrefs = context.dataStore.data.first()
            cachedRestaurantId = initialPrefs[PrefsKeys.RESTAURANT_ID]
        }
        appScope.launch {
            context.dataStore.data.collect { prefs ->
                cachedRestaurantId = prefs[PrefsKeys.RESTAURANT_ID]
            }
        }

        val authInterceptor = Interceptor { chain ->
            val token = cachedToken
            val request = chain.request().newBuilder().apply {
                token?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "application/json")
            }.build()

            chain.proceed(request)
        }

        // Multi-restaurant support: auto-appends ?restaurant_id= to every
        // /seller/* request when the seller has picked one from the picker
        // sheet. The backend falls back to the seller's first owned
        // restaurant when the param is absent.
        val sellerRestaurantInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            if (!path.contains("/seller/") || path.endsWith("/seller/restaurants")) {
                return@Interceptor chain.proceed(req)
            }
            val restaurantId = cachedRestaurantId
            if (restaurantId.isNullOrBlank()) {
                return@Interceptor chain.proceed(req)
            }
            val newUrl = req.url.newBuilder()
                .addQueryParameter("restaurant_id", restaurantId)
                .build()
            chain.proceed(req.newBuilder().url(newUrl).build())
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(sellerRestaurantInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(TokenAuthenticator(context))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}

private class TokenAuthenticator(
    private val context: Context,
) : Authenticator {

    private val lock = Any()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        // Bail after 1 prior attempt to avoid infinite retry loops
        if (responseCount(response) > 1) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null

        synchronized(lock) {
            // Check if another thread already refreshed the token while we
            // waited on the lock. If so, just retry with the new token
            // instead of hitting /auth/refresh again (avoids race condition
            // where N concurrent 401s each trigger a separate refresh).
            val currentToken = NetworkModule.cachedToken
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = NetworkModule.cachedRefreshToken ?: run {
                signalSessionExpired()
                return null
            }

            val newTokens = tryRefresh(refreshToken) ?: run {
                signalSessionExpired()
                return null
            }

            NetworkModule.cachedToken = newTokens.first
            NetworkModule.cachedRefreshToken = newTokens.second
            // Persist to encrypted prefs. SharedPreferences.apply() is async
            // and non-blocking, so no risk of deadlocking OkHttp threads.
            NetworkModule.tokenPrefs?.edit()
                ?.putString("auth_token", newTokens.first)
                ?.putString("refresh_token", newTokens.second)
                ?.apply()

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${newTokens.first}")
                .build()
        }
    }

    private fun tryRefresh(refreshToken: String): Pair<String, String>? {
        val json = JSONObject().put("refresh_token", refreshToken).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = okhttp3.Request.Builder()
            .url(BuildConfig.BASE_URL + "auth/refresh")
            .post(body)
            .build()

        return try {
            refreshClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null

                val bodyString = resp.body?.string()
                if (bodyString.isNullOrBlank()) return@use null

                val parsed = JSONObject(bodyString)
                val token = parsed.optString("token", "")
                val newRefresh = parsed.optString("refresh_token", "")
                if (token.isBlank() || newRefresh.isBlank()) return@use null

                Pair(token, newRefresh)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun signalSessionExpired() {
        NetworkModule.cachedToken = null
        NetworkModule.cachedRefreshToken = null
        NetworkModule.cachedRestaurantId = null
        // Clear encrypted prefs immediately so a cold-start after force-quit
        // cannot re-hydrate stale tokens and land the seller on the dashboard.
        NetworkModule.tokenPrefs?.edit()
            ?.remove("auth_token")
            ?.remove("refresh_token")
            ?.apply()
        // Also clear restaurant ID from DataStore
        appScope.launch {
            context.dataStore.edit { prefs ->
                prefs.remove(PrefsKeys.RESTAURANT_ID)
            }
        }
        NetworkModule.sessionExpired.value = true
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
