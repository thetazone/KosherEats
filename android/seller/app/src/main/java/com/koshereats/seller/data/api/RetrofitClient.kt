package com.koshereats.seller.data.api

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// Non-sensitive prefs only. The active restaurant id is fine on disk in
// cleartext; access/refresh tokens are NOT — those live in TokenProvider's
// EncryptedSharedPreferences (see Finding 1).
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "seller_prefs")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenPrefs

object PrefsKeys {
    // AUTH_TOKEN / REFRESH_TOKEN intentionally removed from the DataStore key set:
    // they are now stored encrypted via TokenProvider, never in the plaintext
    // DataStore. Only the non-sensitive restaurant id remains here.
    val RESTAURANT_ID = stringPreferencesKey("restaurant_id")
}

/**
 * Single source of truth for the seller's auth + refresh tokens, persisted in
 * EncryptedSharedPreferences so they never touch disk in cleartext (Finding 1).
 *
 * Reads/writes mirror the in-memory [NetworkModule.cachedToken] /
 * [NetworkModule.cachedRefreshToken] runtime caches the OkHttp interceptor and
 * [TokenAuthenticator] already rely on, so the rest of the network stack is
 * unchanged: the cache stays the hot path, encrypted prefs are the durable copy.
 */
@Singleton
class TokenProvider @Inject constructor(
    @TokenPrefs private val prefs: SharedPreferences,
) {
    init {
        // Keystore unwrap is disk + crypto I/O — do it off the main thread, then
        // hydrate the runtime caches the interceptors read.
        CoroutineScope(Dispatchers.IO).launch {
            val token = prefs.getString(KEY_AUTH, null)
            val refresh = prefs.getString(KEY_REFRESH, null)
            NetworkModule.cachedToken = token
            NetworkModule.cachedRefreshToken = refresh
        }
    }

    fun getToken(): String? = prefs.getString(KEY_AUTH, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    fun persistTokens(token: String, refreshToken: String) {
        NetworkModule.cachedToken = token
        NetworkModule.cachedRefreshToken = refreshToken
        prefs.edit()
            .putString(KEY_AUTH, token)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    fun clearTokens() {
        NetworkModule.cachedToken = null
        NetworkModule.cachedRefreshToken = null
        prefs.edit()
            .remove(KEY_AUTH)
            .remove(KEY_REFRESH)
            .apply()
    }

    private companion object {
        const val KEY_AUTH = "auth_token"
        const val KEY_REFRESH = "refresh_token"
    }
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
    @TokenPrefs
    fun provideTokenPrefs(@ApplicationContext context: Context): SharedPreferences {
        // MasterKey.build() + EncryptedSharedPreferences.create() both hit the
        // Android Keystore synchronously and can throw after device restore, OS
        // upgrades, or when the keystore is locked. Fall back to an in-memory,
        // session-scoped store (seller is effectively logged-out; tokens
        // re-populate on next successful login) rather than crashing the app.
        // We deliberately do NOT fall back to plain MODE_PRIVATE prefs: the JWT
        // access token and long-lived refresh token must never touch disk in
        // cleartext.
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "seller_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.w("RetrofitClient", "EncryptedSharedPreferences unavailable — using in-memory token store", e)
            InMemorySharedPreferences()
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
        moshi: Moshi,
        tokenProvider: TokenProvider,
    ): OkHttpClient {
        // Prime the token caches off the encrypted store, and the restaurant id
        // off DataStore, before the collector below takes over. Avoids blocking
        // the injection thread (potential ANR on cold start via main thread).
        appScope.launch {
            cachedToken = tokenProvider.getToken()
            cachedRefreshToken = tokenProvider.getRefreshToken()
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
            .authenticator(TokenAuthenticator(context, moshi, tokenProvider))
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
    private val tokenProvider: TokenProvider,
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
        // was never authenticated for this call (e.g. a cold-start race where the
        // token cache hadn't been primed yet, or a public endpoint rejecting an
        // anonymous request). Treating it as a session expiry would clear DataStore
        // and flip sessionExpired, which drives AuthViewModel.clearAuth() →
        // unregisterDevice() — itself a fresh request that can 401 again, forming a
        // feedback loop. If the cache has since been primed with a valid token,
        // retry with it; otherwise give up WITHOUT signalling session-expired so a
        // valid session is never wiped by an early unauthenticated request.
        if (response.request.header("Authorization") == null) {
            val primedToken = NetworkModule.cachedToken ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $primedToken")
                .build()
        }

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
                    // Persist asynchronously to the encrypted store — cachedToken is the
                    // runtime source of truth, so blocking OkHttp dispatcher threads for a
                    // keystore write is unnecessary. Guard: if clearAuth/signalSessionExpired
                    // nulled cachedToken before this coroutine runs, skip the write so we
                    // don't ghost-persist a stale token.
                    appScope.launch {
                        if (NetworkModule.cachedToken == result.accessToken) {
                            tokenProvider.persistTokens(result.accessToken, result.refreshToken)
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
        // Wipe the persisted tokens (encrypted store) and the restaurant id
        // (DataStore) immediately so a cold-start after force-quit cannot
        // re-hydrate the stale session and land the seller on the dashboard.
        tokenProvider.clearTokens()
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

// Process-scoped SharedPreferences used only when the Android Keystore is
// unavailable (device restore, OS upgrade, locked keystore). Nothing is
// persisted to disk, so secrets (access + refresh tokens) never exist in
// cleartext: they live only for the current process and are gone on restart,
// matching the documented "treated as logged-out" intent.
private class InMemorySharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any?>()
    private val listeners = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>(),
    )

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { listeners.add(it) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { listeners.remove(it) }
    }

    private fun notifyChanged(key: String?) {
        for (l in listeners) l.onSharedPreferenceChanged(this, key)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        // Null marks a key staged for removal; a sentinel distinguishes "clear all".
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { pending[key] = REMOVE }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearRequested) {
                val keys = map.keys.toList()
                map.clear()
                keys.forEach { notifyChanged(it) }
            }
            for ((key, value) in pending) {
                if (value === REMOVE || value == null) {
                    map.remove(key)
                } else {
                    map[key] = value
                }
                notifyChanged(key)
            }
        }
    }

    private companion object {
        private val REMOVE = Any()
    }
}
