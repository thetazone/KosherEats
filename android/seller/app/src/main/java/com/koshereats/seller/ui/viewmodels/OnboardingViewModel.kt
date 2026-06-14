package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.models.CreateMenuItemBody
import com.koshereats.seller.data.models.CreateRestaurantRequest
import com.koshereats.seller.data.models.KosherCertification
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.data.models.PresignResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { BASICS, ADDRESS, KOSHER, MENU, REVIEW }

data class OnboardingMenuItem(
    val name: String = "",
    val description: String = "",
    val priceDollars: String = "",
    val category: MenuCategory = MenuCategory.MAINS,
    val isMeat: Boolean = false,
    val isDairy: Boolean = false,
    val isPareve: Boolean = false,
    val imageUrl: String = "",
)

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.BASICS,
    // Basics
    val restaurantName: String = "",
    val description: String = "",
    val pictureUrl: String = "", // Required hero image shown on consumer cards
    val logoUrl: String = "",    // Optional small badge overlayed on the card
    val phone: String = "",
    val email: String = "",
    // Address
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    // Kosher
    val certification: KosherCertification = KosherCertification.OU,
    val certifyingAgency: String = "",
    val isCholovYisroel: Boolean = false,
    val isPasYisroel: Boolean = false,
    val isGlattKosher: Boolean = false,
    val kosherCertificateUrl: String = "",
    // Menu items
    val menuItems: List<OnboardingMenuItem> = emptyList(),
    // UI
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // Idempotency state so a retry after a partial failure resumes rather than
    // re-POSTing. Once the restaurant is created we never create it again; once a
    // category is created we reuse its id; once an item is created we skip it.
    private var createdRestaurantId: String? = null
    private val createdCategoryIds = mutableMapOf<String, String>()
    private val createdItemKeys = mutableSetOf<String>()

    fun updateBasics(
        name: String,
        description: String,
        pictureUrl: String,
        logoUrl: String,
        phone: String,
        email: String,
    ) {
        _state.value = _state.value.copy(
            restaurantName = name.take(200),
            description = description.take(2000),
            pictureUrl = pictureUrl,
            logoUrl = logoUrl,
            phone = phone.take(20),
            email = email,
        )
    }

    fun updateAddress(street: String, city: String, state: String, zipCode: String) {
        _state.value = _state.value.copy(
            street = street, city = city, state = state, zipCode = zipCode,
        )
    }

    fun updateKosher(
        certification: KosherCertification,
        agency: String,
        cholovYisroel: Boolean,
        pasYisroel: Boolean,
        glattKosher: Boolean,
        certificateUrl: String = "",
    ) {
        _state.value = _state.value.copy(
            certification = certification,
            certifyingAgency = agency,
            isCholovYisroel = cholovYisroel,
            isPasYisroel = pasYisroel,
            isGlattKosher = glattKosher,
            kosherCertificateUrl = certificateUrl,
        )
    }

    fun addMenuItem(item: OnboardingMenuItem) {
        _state.value = _state.value.copy(
            menuItems = _state.value.menuItems + item,
        )
    }

    fun removeMenuItem(index: Int) {
        _state.value = _state.value.copy(
            menuItems = _state.value.menuItems.filterIndexed { i, _ -> i != index },
        )
    }

    fun nextStep() {
        val next = when (_state.value.step) {
            OnboardingStep.BASICS -> OnboardingStep.ADDRESS
            OnboardingStep.ADDRESS -> OnboardingStep.KOSHER
            OnboardingStep.KOSHER -> OnboardingStep.MENU
            OnboardingStep.MENU -> OnboardingStep.REVIEW
            OnboardingStep.REVIEW -> return
        }
        _state.value = _state.value.copy(step = next, error = null)
    }

    fun previousStep() {
        val prev = when (_state.value.step) {
            OnboardingStep.BASICS -> return
            OnboardingStep.ADDRESS -> OnboardingStep.BASICS
            OnboardingStep.KOSHER -> OnboardingStep.ADDRESS
            OnboardingStep.MENU -> OnboardingStep.KOSHER
            OnboardingStep.REVIEW -> OnboardingStep.MENU
        }
        _state.value = _state.value.copy(step = prev, error = null)
    }

    fun setError(msg: String?) {
        _state.value = _state.value.copy(error = msg)
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        return try {
            val response = apiService.presignUpload(mapOf("kind" to kind, "content_type" to contentType))
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    fun submit() {
        val s = _state.value
        if (s.isSubmitting) return
        if (s.restaurantName.isBlank()) {
            _state.value = s.copy(error = "Restaurant name is required", step = OnboardingStep.BASICS)
            return
        }
        if (s.pictureUrl.isBlank()) {
            _state.value = s.copy(error = "Restaurant picture is required", step = OnboardingStep.BASICS)
            return
        }
        if (s.email.trim().isNotEmpty() &&
            !android.util.Patterns.EMAIL_ADDRESS.matcher(s.email.trim()).matches()) {
            _state.value = s.copy(error = "Please enter a valid restaurant email address", step = OnboardingStep.BASICS)
            return
        }
        if (s.phone.trim().isNotEmpty() && s.phone.trim().filter { it.isDigit() }.length !in 7..15) {
            _state.value = s.copy(error = "Please enter a valid restaurant phone number", step = OnboardingStep.BASICS)
            return
        }
        if (s.kosherCertificateUrl.isBlank()) {
            _state.value = s.copy(error = "Kosher certificate photo is required", step = OnboardingStep.KOSHER)
            return
        }
        _state.value = s.copy(isSubmitting = true, error = null)

        viewModelScope.launch {
            try {
                // Create the restaurant only once. On a retry after a partial
                // failure, createdRestaurantId is already set, so we skip the POST
                // (preventing a duplicate restaurant) and resume from menu creation.
                var restaurantId = createdRestaurantId
                if (restaurantId == null) {
                    val req = CreateRestaurantRequest(
                        name = s.restaurantName.trim(),
                        description = s.description.trim(),
                        imageUrl = s.pictureUrl,
                        logoUrl = s.logoUrl,
                        phone = s.phone.trim(),
                        email = s.email.trim(),
                        street = s.street.trim(),
                        city = s.city.trim(),
                        state = s.state.trim(),
                        zipCode = s.zipCode.trim(),
                        kosherCertification = s.certification,
                        certifyingAgency = s.certifyingAgency.trim(),
                        isCholovYisroel = s.isCholovYisroel,
                        isPasYisroel = s.isPasYisroel,
                        isGlattKosher = s.isGlattKosher,
                        kosherCertificateUrl = s.kosherCertificateUrl,
                    )

                    val restResponse = apiService.createRestaurant(req)
                    if (!restResponse.isSuccessful) {
                        _state.value = _state.value.copy(
                            isSubmitting = false,
                            error = "Failed to create restaurant. Please try again.",
                        )
                        return@launch
                    }
                    restaurantId = restResponse.body()?.id
                    createdRestaurantId = restaurantId
                }

                // Pin the restaurant ID so the sellerRestaurantInterceptor targets it
                // for all category/menu-item writes below instead of falling back to
                // the backend's undefined "first restaurant" ordering.
                restaurantId?.let { NetworkModule.cachedRestaurantId = it }

                val grouped = s.menuItems.groupBy {
                    it.category.name.lowercase().replace('_', ' ')
                        .split(' ')
                        .joinToString(" ") { word -> word.replaceFirstChar { c -> c.uppercase() } }
                }
                var createdCount = 0
                var failedCount = 0
                for ((categoryName, categoryItems) in grouped) {
                    val sellableItems = categoryItems.filter {
                        val p = ((it.priceDollars.toDoubleOrNull() ?: 0.0) * 100).roundToInt()
                        p > 0 && it.name.isNotBlank()
                    }
                    if (sellableItems.isEmpty()) continue

                    // Reuse a category created on an earlier attempt instead of
                    // POSTing a duplicate; create it only if not yet recorded.
                    val categoryId = createdCategoryIds[categoryName] ?: run {
                        val catResponse = runCancellable {
                            apiService.createCategory(mapOf("name" to categoryName))
                        }
                        val newId = if (catResponse?.isSuccessful == true) catResponse.body()?.id else null
                        if (newId != null) createdCategoryIds[categoryName] = newId
                        newId
                    }
                    if (categoryId == null) {
                        failedCount += sellableItems.count { "$categoryName:${it.name.trim()}" !in createdItemKeys }
                        continue
                    }

                    for (item in sellableItems) {
                        val itemKey = "$categoryName:${item.name.trim()}"
                        if (itemKey in createdItemKeys) continue
                        val priceCents = ((item.priceDollars.toDoubleOrNull() ?: 0.0) * 100).roundToInt()
                        val itemResponse = runCancellable {
                            apiService.createMenuItemWithCategory(
                                CreateMenuItemBody(
                                    categoryId = categoryId,
                                    name = item.name.trim(),
                                    description = item.description.trim(),
                                    price = priceCents,
                                    imageUrl = item.imageUrl,
                                    isMeat = item.isMeat,
                                    isDairy = item.isDairy,
                                    isKosherPareve = item.isPareve,
                                ),
                            )
                        }
                        if (itemResponse?.isSuccessful == true) {
                            createdItemKeys += itemKey
                            createdCount++
                        } else {
                            failedCount++
                        }
                    }
                }

                val partialError = if (failedCount > 0) {
                    "Created $createdCount of ${createdCount + failedCount} items — use Update Menu to add the rest."
                } else null
                _state.value = _state.value.copy(isSubmitting = false, isComplete = true, error = partialError)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = "Connection error: ${e.message}",
                )
            }
        }
    }

    /**
     * Runs a single network call, returning null when it throws (a blip on one
     * category/item must not abort the whole menu loop — it's counted as a
     * partial failure). CancellationException is rethrown so coroutine
     * cancellation stays cooperative.
     */
    private suspend fun <T> runCancellable(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
