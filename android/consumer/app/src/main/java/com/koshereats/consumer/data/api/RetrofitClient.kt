package com.koshereats.consumer.data.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.koshereats.consumer.BuildConfig
import com.koshereats.consumer.data.session.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Qualifier
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenPrefs

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "koshereats_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val USER_ID = stringPreferencesKey("user_id")
}

@Singleton
class TokenProvider @Inject constructor(
    @TokenPrefs private val prefs: SharedPreferences,
) {
    @Volatile var token: String? = null
        private set

    @Volatile var refreshToken: String? = null
        private set

    // Completes once the encrypted-prefs read finishes; callers that need the
    // token before it's available can suspend on awaitToken() instead of racing.
    private val loaded = CompletableDeferred<Unit>()

    init {
        // Keystore unwrap is disk+crypto I/O — do it off the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            token = prefs.getString("auth_token", null)
            refreshToken = prefs.getString("refresh_token", null)
            loaded.complete(Unit)
        }
    }

    fun getCachedToken(): String? = token

    suspend fun awaitToken(): String? {
        loaded.await()
        return token
    }

    fun persistNewTokens(newToken: String, newRefreshToken: String) {
        token = newToken
        refreshToken = newRefreshToken
        prefs.edit()
            .putString("auth_token", newToken)
            .putString("refresh_token", newRefreshToken)
            .apply()
    }

    fun clearTokens() {
        token = null
        refreshToken = null
        prefs.edit()
            .remove("auth_token")
            .remove("refresh_token")
            .apply()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    @TokenPrefs
    fun provideTokenPrefs(@ApplicationContext context: Context): SharedPreferences {
        // MasterKey.build() + EncryptedSharedPreferences.create() both hit the
        // Android Keystore synchronously and can throw after device restore, OS
        // upgrades, or when the keystore is locked.  Fall back to an in-memory,
        // session-scoped store (user is effectively logged-out; tokens re-populate
        // on next successful login) rather than crashing the app.  We deliberately
        // do NOT write tokens to plain MODE_PRIVATE prefs: the JWT access token and
        // long-lived refresh token must never touch disk in cleartext.
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                "koshereats_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // Keystore recovered after a previous fallback: wipe any cleartext
            // token copy left on disk by an older build that fell back to plain
            // prefs, so a still-valid refresh token can't linger unencrypted.
            context.deleteSharedPreferences("koshereats_fallback_prefs")
            encrypted
        } catch (e: Exception) {
            // Surface the silent fallback instead of swallowing it — a device
            // whose Keystore is permanently broken would otherwise look healthy.
            runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
            InMemorySharedPreferences()
        }
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenProvider: TokenProvider): Interceptor {
        return Interceptor { chain ->
            // Read the in-memory cache only — no blocking. If the token hasn't
            // loaded yet (unlikely after KosherEatsApp pre-warms it), the request
            // goes out without an Authorization header and TokenAuthenticator retries.
            val token = tokenProvider.getCachedToken()
            val request = chain.request().newBuilder().apply {
                header("Accept", "application/json")
                token?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: Interceptor,
        tokenProvider: TokenProvider,
        sessionManager: SessionManager,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(TokenAuthenticator(tokenProvider, sessionManager))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(FallbackEnumAdapterFactory)
            .create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}

// Process-scoped SharedPreferences used only when the Android Keystore is
// unavailable.  Nothing is persisted to disk, so secrets (access + refresh
// tokens) never exist in cleartext: they live only for the current process and
// are gone on restart, matching the documented "treated as logged-out" intent.
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

// Enum TypeAdapter factory: returns the UNKNOWN sentinel for any enum string the
// backend sends that isn't in our @SerializedName set, and logs the value once.
// Applies to every enum that declares an UNKNOWN constant.
private object FallbackEnumAdapterFactory : TypeAdapterFactory {
    private val logged = ConcurrentHashMap<String, Unit>()

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        if (!rawType.isEnum) return null

        @Suppress("UNCHECKED_CAST")
        val constants = rawType.enumConstants as? Array<out Enum<*>> ?: return null
        val unknown = constants.find { it.name == "UNKNOWN" } ?: return null

        val lookup = HashMap<String, Enum<*>>()
        for (c in constants) {
            val field = try { rawType.getField(c.name) } catch (_: NoSuchFieldException) { null }
            val ann = field?.getAnnotation(SerializedName::class.java)
            if (ann != null) {
                lookup[ann.value] = c
                ann.alternate.forEach { alt -> lookup[alt] = c }
            } else {
                lookup[c.name] = c
            }
        }

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) { out.nullValue(); return }
                val e = value as Enum<*>
                val field = try { rawType.getField(e.name) } catch (_: NoSuchFieldException) { null }
                val ann = field?.getAnnotation(SerializedName::class.java)
                out.value(ann?.value ?: e.name)
            }

            override fun read(`in`: JsonReader): T {
                if (`in`.peek() == JsonToken.NULL) {
                    `in`.nextNull()
                    @Suppress("UNCHECKED_CAST")
                    return unknown as T
                }
                val raw = `in`.nextString()
                val result = lookup[raw]
                if (result == null) {
                    if (logged.putIfAbsent("${rawType.simpleName}:$raw", Unit) == null) {
                        Log.w("KosherEats", "Unknown ${rawType.simpleName} value '$raw', falling back to UNKNOWN")
                    }
                    @Suppress("UNCHECKED_CAST")
                    return unknown as T
                }
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        } as TypeAdapter<T>
    }
}

private class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
    private val sessionManager: SessionManager,
) : Authenticator {

    private val lock = Any()

    // Tracks whether a logout was already dispatched so concurrent threads
    // waiting on the lock don't emit duplicate LOGOUT events.
    @Volatile private var logoutDispatched = false

    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        // Cap retry chain to prevent an infinite refresh→401 loop from wedging the OkHttp dispatcher.
        if (responseCount(response) >= 2) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null

        // No Authorization header means the request fired before the token was loaded
        // (cold-start race). If a token is now available, retry with it instead of failing.
        if (response.request.header("Authorization") == null) {
            val t = tokenProvider.token ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $t")
                .build()
        }

        synchronized(lock) {
            // If another thread already dispatched logout, suppress duplicate events.
            // But if new tokens have arrived since (user re-logged in), reset and proceed.
            if (logoutDispatched) {
                if (tokenProvider.token == null) return null
                logoutDispatched = false  // New credentials present — allow normal refresh.
            }

            val currentToken = tokenProvider.token
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            // clearTokens() sets both token and refreshToken to null.  A
            // concurrent thread that enters here after clearTokens() would see
            // null refreshToken AND null token — skip the duplicate logout.
            val refreshToken = tokenProvider.refreshToken ?: run {
                if (tokenProvider.token == null) return null
                logoutDispatched = true
                sessionManager.signalLogout()
                return null
            }

            return when (val result = tryRefresh(refreshToken)) {
                is RefreshResult.Success -> {
                    logoutDispatched = false
                    tokenProvider.persistNewTokens(result.accessToken, result.refreshToken)
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.accessToken}")
                        .build()
                }
                RefreshResult.Unauthorized -> {
                    tokenProvider.clearTokens()
                    logoutDispatched = true
                    sessionManager.signalLogout()
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
                        if (bodyStr == null) {
                            Log.w("KosherEats", "tryRefresh: null body on ${resp.code}")
                            return@use RefreshResult.Failure("null_body")
                        }
                        try {
                            val parsed = JSONObject(bodyStr)
                            val token = parsed.optString("token", "")
                            val refresh = parsed.optString("refresh_token", "")
                            if (token.isBlank() || refresh.isBlank()) {
                                Log.w("KosherEats", "tryRefresh: response missing token fields")
                                RefreshResult.Failure("missing_fields")
                            } else {
                                RefreshResult.Success(token, refresh)
                            }
                        } catch (e: JSONException) {
                            Log.w("KosherEats", "tryRefresh: malformed JSON in refresh response — ${e.message}")
                            RefreshResult.Failure("json_parse")
                        } catch (e: Exception) {
                            Log.w("KosherEats", "tryRefresh: unexpected parse error", e)
                            RefreshResult.Failure("parse_error")
                        }
                    }
                    resp.code == 401 -> RefreshResult.Unauthorized
                    else -> {
                        Log.w("KosherEats", "tryRefresh: unexpected HTTP ${resp.code}")
                        RefreshResult.Failure("http_${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("KosherEats", "tryRefresh: network error — ${e.message}")
            RefreshResult.Failure("network")
        }
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
