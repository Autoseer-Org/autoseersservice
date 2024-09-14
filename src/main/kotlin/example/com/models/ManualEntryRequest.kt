package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class ManualEntryRequest(
    val year: String,
    val make: String,
    val model: String,
    val mileage: String?,
)

@Serializable
data class ManualEntryResponse(
    val failure: String? = null,
)