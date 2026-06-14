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
import com.koshereats.seller.data.models.RefreshResponse
import com.koshereats.seller.data.models.UnknownFallbackEnumAdapterFactory
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

    // Emitted by RestaurantPickerViewModel.select() whenever the active restaurant changes.
    // OrdersViewModel, MenuViewModel, and DealsViewModel collect this to reset + reload.
    val restaurantChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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
        moshi: Moshi,
    ): OkHttpClient {
        // Prime caches on IO before the collectors below take over; avoids
        // blocking the injection thread (potential ANR on cold start via main thread).
        appScope.launch {
            val initialPrefs = context.dataStore.data.first()
            cachedToken = initialPrefs[PrefsKeys.AUTH_TOKEN]
            cachedRefreshToken = initialPrefs[PrefsKeys.REFRESH_TOKEN]
            cachedRestaurantId = initialPrefs[PrefsKeys.RESTAURANT_ID]
        }

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
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(sellerRestaurantInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(TokenAuthenticator(context, moshi))
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
    private val moshi: Moshi,
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
        // OkHttp Authenticator can be invoked up to 2-3 times per request when concurrent
        // 401s come in; cap aggressively to avoid an infinite refresh loop wedging the dispatcher.
        if (responseCount(response) >= 2) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null

        // A 401 on a request that carried no Authorization header means the app
        // was never authenticated for this call (e.g. a cold-start race, or a
        // public endpoint rejecting an anonymous request). Treating it as a
        // session expiry would clear DataStore and flip sessionExpired, which
        // drives AuthViewModel.clearAuth() → unregisterDevice() — itself a fresh
        // request that can 401 again, forming a feedback loop. Just give up.
        if (response.request.header("Authorization") == null) return null

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

            return when (val result = tryRefresh(refreshToken)) {
                is RefreshResult.Success -> {
                    android.util.Log.i("RetrofitClient", "Token refresh succeeded")
                    NetworkModule.cachedToken = result.accessToken
                    NetworkModule.cachedRefreshToken = result.refreshToken
                    // Persist asynchronously — cachedToken is the runtime source of truth,
                    // so blocking OkHttp dispatcher threads for a DataStore write is unnecessary.
                    // Guard: if clearAuth/signalSessionExpired nulled cachedToken before this
                    // coroutine runs, skip the write so we don't ghost-persist a stale token.
                    appScope.launch {
                        if (NetworkModule.cachedToken == result.accessToken) {
                            context.dataStore.edit { prefs ->
                                prefs[PrefsKeys.AUTH_TOKEN] = result.accessToken
                                prefs[PrefsKeys.REFRESH_TOKEN] = result.refreshToken
                            }
                        }
                    }
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.accessToken}")
                        .build()
                }
                // Only a genuine 401 from /auth/refresh means the refresh token is
                // dead — log the seller out. Transient failures (network, 5xx,
                // malformed body) must NOT destroy a still-valid refresh token.
                RefreshResult.Unauthorized -> {
                    signalSessionExpired()
                    null
                }
                is RefreshResult.Failure -> null
            }
        }
    }

    private sealed class RefreshResult {
        data class Success(val accessToken: String, val refreshToken: String) : RefreshResult()
        object Unauthorized : RefreshResult()
        data class Failure(val reason: String) : RefreshResult()
    }

    private fun tryRefresh(refreshToken: String): RefreshResult {
        val json = JSONObject().put("refresh_token", refreshToken).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = okhttp3.Request.Builder()
            .url(BuildConfig.BASE_URL + "auth/refresh")
            .post(body)
            .build()

        return try {
            refreshClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val bodyStr = resp.body?.string()
                        if (bodyStr.isNullOrBlank()) {
                            android.util.Log.w("RetrofitClient", "Token refresh: empty body")
                            return@use RefreshResult.Failure("empty_body")
                        }
                        val adapter = moshi.adapter(RefreshResponse::class.java)
                        val parsed = try {
                            adapter.fromJson(bodyStr)
                        } catch (e: Exception) {
                            android.util.Log.w("RetrofitClient", "Token refresh: bad JSON", e)
                            return@use RefreshResult.Failure("json_parse")
                        } ?: return@use RefreshResult.Failure("null_parse")
                        val token = parsed.token.ifBlank { return@use RefreshResult.Failure("missing_token") }
                        val refresh = parsed.refreshToken.ifBlank { return@use RefreshResult.Failure("missing_refresh") }
                        RefreshResult.Success(token, refresh)
                    }
                    resp.code == 401 -> {
                        android.util.Log.w("RetrofitClient", "Token refresh rejected: HTTP 401")
                        RefreshResult.Unauthorized
                    }
                    else -> {
                        android.util.Log.w("RetrofitClient", "Token refresh failed: HTTP ${resp.code}")
                        RefreshResult.Failure("http_${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RetrofitClient", "Token refresh threw", e)
            RefreshResult.Failure("network")
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
