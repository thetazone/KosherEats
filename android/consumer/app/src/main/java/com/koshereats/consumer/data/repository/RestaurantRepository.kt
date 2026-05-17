package com.koshereats.consumer.data.repository

import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: Int? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

@Singleton
class RestaurantRepository @Inject constructor(
    private val apiService: ApiService,
) {
    fun getRestaurants(
        page: Int = 1,
        latitude: Double? = null,
        longitude: Double? = null,
        cuisine: String? = null,
        dietaryType: String? = null,
        kosherCertification: String? = null,
        isCholovYisroel: Boolean? = null,
        isPasYisroel: Boolean? = null,
        isGlattKosher: Boolean? = null,
        sortBy: String? = null,
    ): Flow<Resource<List<Restaurant>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurants(
                page = page,
                latitude = latitude,
                longitude = longitude,
                cuisine = cuisine,
                dietaryType = dietaryType,
                kosherCertification = kosherCertification,
                isCholovYisroel = isCholovYisroel,
                isPasYisroel = isPasYisroel,
                isGlattKosher = isGlattKosher,
                sortBy = sortBy,
            )
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error("Failed to load restaurants", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun searchRestaurants(
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Flow<Resource<List<Restaurant>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.searchRestaurants(query, latitude, longitude)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error("Search failed", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun getSuggestedRestaurants(
        limit: Int = 10,
    ): Flow<Resource<List<Restaurant>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getSuggestedRestaurants(limit = limit)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error("Failed to load suggestions", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun getRestaurant(restaurantId: String): Flow<Resource<Restaurant>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurant(restaurantId)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("Restaurant not found"))
            } else {
                emit(Resource.Error("Failed to load restaurant", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun getRestaurantMenu(restaurantId: String): Flow<Resource<List<MenuCategory>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurantMenu(restaurantId)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No menu data"))
            } else {
                emit(Resource.Error("Failed to load menu", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun getOrders(
        page: Int = 1,
        status: String? = null,
    ): Flow<Resource<List<Order>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getOrders(page = page, status = status)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No orders found"))
            } else {
                emit(Resource.Error("Failed to load orders", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun getRestaurantDeals(restaurantId: String): Flow<Resource<List<Deal>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurantDeals(restaurantId)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No deals found"))
            } else {
                emit(Resource.Error("Failed to load deals", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }

    fun createOrder(request: CreateOrderRequest): Flow<Resource<Order>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.createOrder(request)
            if (response.isSuccessful) {
                response.body()?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("Order creation failed"))
            } else {
                emit(Resource.Error("Failed to create order", response.code()))
            }
        } catch (e: IOException) {
            emit(Resource.Error("Network error", null))
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Unexpected error"))
        }
    }
}
