package com.koshereats.seller.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.koshereats.seller.BuildConfig
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
import kotlinx.coroutines.flow.MutableStateFlow
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

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var cachedToken: String? = null
    @Volatile var cachedRefreshToken: String? = null
    @Volatile var cachedRestaurantId: String? = null

    val sessionExpired = MutableStateFlow(false)

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient {
        appScope.launch {
            context.dataStore.data.collect { prefs ->
                cachedToken = prefs[PrefsKeys.AUTH_TOKEN]
                cachedRefreshToken = prefs[PrefsKeys.REFRESH_TOKEN]
            }
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
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
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
        .build()

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        if (responseCount(response) > 1) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null

        synchronized(lock) {
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
            // Persist asynchronously — cachedToken is the runtime source of truth,
            // so blocking OkHttp dispatcher threads for a DataStore write is unnecessary.
            appScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[PrefsKeys.AUTH_TOKEN] = newTokens.first
                    prefs[PrefsKeys.REFRESH_TOKEN] = newTokens.second
                }
            }

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
                if (resp.isSuccessful) {
                    val parsed = JSONObject(resp.body?.string() ?: return null)
                    Pair(parsed.getString("token"), parsed.getString("refresh_token"))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun signalSessionExpired() {
        NetworkModule.cachedToken = null
        NetworkModule.cachedRefreshToken = null
        NetworkModule.cachedRestaurantId = null
        // Clear DataStore immediately so a cold-start after force-quit cannot
        // re-hydrate the stale tokens and land the seller on the dashboard.
        appScope.launch {
            context.dataStore.edit { it.clear() }
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
