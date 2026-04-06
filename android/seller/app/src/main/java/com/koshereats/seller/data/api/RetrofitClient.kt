package com.koshereats.seller.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "seller_prefs")

object PrefsKeys {
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val RESTAURANT_ID = stringPreferencesKey("restaurant_id")
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

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
        val authInterceptor = Interceptor { chain ->
            val token = runBlocking {
                context.dataStore.data.map { prefs ->
                    prefs[PrefsKeys.AUTH_TOKEN]
                }.first()
            }

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
        // restaurant when the param is absent. Mirrors the iOS
        // SelectedRestaurant.appendQuery pattern — same cross-platform
        // semantics without touching every ViewModel.
        val sellerRestaurantInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            if (!path.contains("/seller/") || path.endsWith("/seller/restaurants")) {
                // Skip the picker's own list endpoint and anything outside /seller/.
                return@Interceptor chain.proceed(req)
            }
            val restaurantId = runBlocking {
                context.dataStore.data.map { it[PrefsKeys.RESTAURANT_ID] }.first()
            }
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
