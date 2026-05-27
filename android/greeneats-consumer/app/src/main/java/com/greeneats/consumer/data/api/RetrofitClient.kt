package com.greeneats.consumer.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.greeneats.consumer.BuildConfig
import com.greeneats.consumer.data.models.OrderStatus
import com.greeneats.consumer.data.session.SessionManager
import java.lang.reflect.Type
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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "greeneats_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val USER_ID = stringPreferencesKey("user_id")
}

@Singleton
class TokenProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope appScope: CoroutineScope
) {
    @Volatile var token: String? = null
        private set

    @Volatile var refreshToken: String? = null
        private set

    private val _initialized = CompletableDeferred<Unit>()

    init {
        appScope.launch {
            val prefs = dataStore.data.first()
            token = prefs[PrefsKeys.AUTH_TOKEN]
            refreshToken = prefs[PrefsKeys.REFRESH_TOKEN]
            _initialized.complete(Unit)
            dataStore.data.collect { p ->
                token = p[PrefsKeys.AUTH_TOKEN]
                refreshToken = p[PrefsKeys.REFRESH_TOKEN]
            }
        }
    }

    suspend fun awaitToken(): String? {
        _initialized.await()
        return token
    }

    /**
     * Persists new auth tokens to DataStore.
     *
     * WARNING: [runBlocking] here risks deadlocking an OkHttp thread when called
     * from [TokenAuthenticator.authenticate], because OkHttp's thread pool is
     * bounded and DataStore I/O contends for the same Dispatchers.IO pool.
     * However, DataStore's `edit` is a suspend function and OkHttp's
     * [okhttp3.Authenticator] interface is synchronous, so there is no way to
     * call `edit` without bridging to a coroutine. The in-memory volatile fields
     * are updated first so subsequent requests see the new token immediately;
     * the DataStore write is a durability concern. If this becomes a production
     * issue, replace with a fire-and-forget `appScope.launch` (accepting that a
     * process kill before the write completes would lose the tokens on disk).
     */
    fun persistNewTokens(newToken: String, newRefreshToken: String) {
        token = newToken
        refreshToken = newRefreshToken
        runBlocking {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.AUTH_TOKEN] = newToken
                prefs[PrefsKeys.REFRESH_TOKEN] = newRefreshToken
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
            val request = chain.request().newBuilder().apply {
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "application/json")
                tokenProvider.token?.let { addHeader("Authorization", "Bearer $it") }
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
    fun provideGson(): Gson = GsonBuilder()
        .registerTypeAdapter(OrderStatus::class.java, OrderStatusDeserializer())
        .create()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
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

/**
 * Maps unknown order-status strings from the backend to [OrderStatus.UNKNOWN]
 * instead of letting Gson return null (which crashes Kotlin non-null fields).
 */
private class OrderStatusDeserializer : JsonDeserializer<OrderStatus> {
    private val lookup: Map<String, OrderStatus> = OrderStatus.entries.associateBy { entry ->
        try {
            val field = OrderStatus::class.java.getField(entry.name)
            field.getAnnotation(com.google.gson.annotations.SerializedName::class.java)?.value ?: entry.name
        } catch (_: Exception) { entry.name }
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): OrderStatus {
        val raw = json.asString
        return lookup[raw] ?: OrderStatus.UNKNOWN
    }
}

private class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
    private val sessionManager: SessionManager,
) : Authenticator {

    private val lock = Any()

    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
        if (responseCount(response) > 1) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null

        synchronized(lock) {
            val currentToken = tokenProvider.token
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = tokenProvider.refreshToken ?: run {
                sessionManager.signalLogout()
                return null
            }

            val newTokens = tryRefresh(refreshToken) ?: run {
                sessionManager.signalLogout()
                return null
            }

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
