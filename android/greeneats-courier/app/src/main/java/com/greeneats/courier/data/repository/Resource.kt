package com.greeneats.courier.data.repository

class RoleMismatchException(message: String) : Exception(message)

/**
 * Resource wraps the result of a network call. Mirrors the pattern used in
 * the consumer app so ViewModels handle loading / success / error uniformly.
 */
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: Int? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

internal fun errorMessage(response: retrofit2.Response<*>, fallback: String): String {
    return try {
        val body = response.errorBody()?.string().orEmpty()
        // Go backend returns {"error":"..."}; pull that out best-effort.
        val match = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)
        match?.groupValues?.get(1) ?: fallback
    } catch (_: Exception) {
        fallback
    }
}
