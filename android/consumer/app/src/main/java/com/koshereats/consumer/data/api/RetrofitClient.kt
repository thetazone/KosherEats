package com.koshereats.consumer.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Qualifier
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "koshereats_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val USER_ID = stringPreferencesKey("user_id")
}

@Singleton
class TokenProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val appScope: CoroutineScope
) {
    @Volatile var token: String? = null
        private set

    @Volatile var refreshToken: String? = null
        private set

    private val _initialized = CompletableDeferred<Unit>()

    init {
        appScope.launch {
            try {
                val prefs = dataStore.data.first()
                token = prefs[PrefsKeys.AUTH_TOKEN]
                refreshToken = prefs[PrefsKeys.REFRESH_TOKEN]
                _initialized.complete(Unit)
            } finally {
                // Ensures awaitToken() never hangs if DataStore throws on startup.
                // token/refreshToken remain null so requests proceed unauthenticated.
                if (!_initialized.isCompleted) {
                    android.util.Log.e("TokenProvider", "DataStore init failed; tokens unavailable")
                    _initialized.complete(Unit)
                }
            }
        }
    }

    suspend fun awaitToken(): String? {
        _initialized.await()
        return token
    }

    fun persistNewTokens(newToken: String, newRefreshToken: String) {
        token = newToken
        refreshToken = newRefreshToken
        appScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.AUTH_TOKEN] = newToken
                prefs[PrefsKeys.REFRESH_TOKEN] = newRefreshToken
            }
        }
    }

    fun clearTokens() {
        token = null
        refreshToken = null
        appScope.launch {
            dataStore.edit { prefs ->
                prefs.remove(PrefsKeys.AUTH_TOKEN)
                prefs.remove(PrefsKeys.REFRESH_TOKEN)
                prefs.remove(PrefsKeys.USER_ID)
            }
        }
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
    fun provideAuthInterceptor(tokenProvider: TokenProvider): Interceptor {
        return Interceptor { chain ->
            // Block until DataStore has finished loading so the first cold-start
            // request always carries the correct Authorization header. The deferred
            // completes in <50 ms on first call and is instant on all subsequent calls.
            val token = runBlocking { tokenProvider.awaitToken() }
            val request = chain.request().newBuilder().apply {
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "application/json")
                token?.let { addHeader("Authorization", "Bearer $it") }
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
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
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
        .build()

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        if (responseCount(response) > 1) return null

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

            val newTokens = tryRefresh(refreshToken) ?: run {
                tokenProvider.clearTokens()
                logoutDispatched = true
                sessionManager.signalLogout()
                return null
            }

            logoutDispatched = false
            tokenProvider.persistNewTokens(newTokens.first, newTokens.second)

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
