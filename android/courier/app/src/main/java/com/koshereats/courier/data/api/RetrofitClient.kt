package com.koshereats.courier.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.koshereats.courier.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// DataStore for auth tokens. Namespaced so it doesn't collide with a consumer
// or seller app sharing the same device.
val Context.courierDataStore: DataStore<Preferences> by preferencesDataStore(name = "koshereats_courier_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
}

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.courierDataStore

    suspend fun getAccessToken(): String? =
        dataStore.data.map { it[PrefsKeys.AUTH_TOKEN] }.first()

    suspend fun save(access: String, refresh: String) {
        dataStore.edit {
            it[PrefsKeys.AUTH_TOKEN] = access
            it[PrefsKeys.REFRESH_TOKEN] = refresh
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(PrefsKeys.AUTH_TOKEN)
            it.remove(PrefsKeys.REFRESH_TOKEN)
        }
    }

    suspend fun isAuthenticated(): Boolean = getAccessToken() != null
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAuthInterceptor(store: TokenStore): Interceptor = Interceptor { chain ->
        // runBlocking here is fine — interceptor already runs on OkHttp's worker thread.
        val token = runBlocking { store.getAccessToken() }
        val req = chain.request().newBuilder().apply {
            addHeader("Content-Type", "application/json")
            addHeader("Accept", "application/json")
            if (token != null) addHeader("Authorization", "Bearer $token")
        }.build()
        chain.proceed(req)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(auth: Interceptor): OkHttpClient {
        val b = OkHttpClient.Builder()
            .addInterceptor(auth)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            b.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        }
        return b.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttp: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)
}
