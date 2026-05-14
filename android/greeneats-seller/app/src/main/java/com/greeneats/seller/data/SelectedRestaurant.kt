package com.greeneats.seller.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.greeneats.seller.data.api.PrefsKeys
import com.greeneats.seller.data.api.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent current-restaurant selection for multi-restaurant sellers.
 *
 * Mirrors iOS `SelectedRestaurant.shared` — stores the active restaurant id
 * in DataStore so it survives app restarts. Every seller endpoint
 * automatically appends `?restaurant_id=` via `sellerRestaurantInterceptor`,
 * so ViewModels never have to pass it explicitly.
 *
 * When nothing is set (first launch / single-restaurant seller), the backend
 * falls back to the seller's first owned restaurant.
 */
object SelectedRestaurant {
    fun flow(context: Context): Flow<String?> =
        context.dataStore.data.map { it[PrefsKeys.RESTAURANT_ID] }

    suspend fun set(context: Context, id: String) {
        context.dataStore.edit { it[PrefsKeys.RESTAURANT_ID] = id }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.remove(PrefsKeys.RESTAURANT_ID) }
    }
}
