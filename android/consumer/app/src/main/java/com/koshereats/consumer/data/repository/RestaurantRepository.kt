package com.koshereats.consumer.data.repository

import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    ): Flow<Resource<PaginatedResponse<Restaurant>>> = flow {
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
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to load restaurants", response.code()))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
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
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Search failed"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }

    fun getFeaturedRestaurants(
        latitude: Double? = null,
        longitude: Double? = null,
    ): Flow<Resource<List<Restaurant>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getFeaturedRestaurants(latitude, longitude)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No data returned"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to load featured restaurants"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }

    fun getRestaurant(restaurantId: String): Flow<Resource<Restaurant>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurant(restaurantId)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("Restaurant not found"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to load restaurant"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }

    fun getRestaurantMenu(restaurantId: String): Flow<Resource<List<MenuCategory>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getRestaurantMenu(restaurantId)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No menu data"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to load menu"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }

    fun getOrders(
        page: Int = 1,
        status: String? = null,
    ): Flow<Resource<PaginatedResponse<Order>>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.getOrders(page, status)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("No orders found"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to load orders"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }

    fun createOrder(request: CreateOrderRequest): Flow<Resource<Order>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService.createOrder(request)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { emit(Resource.Success(it)) }
                    ?: emit(Resource.Error("Order creation failed"))
            } else {
                emit(Resource.Error(response.body()?.error ?: "Failed to create order"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(e.localizedMessage ?: "Network error"))
        }
    }
}
