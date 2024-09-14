package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class BookingRequest(
    val id: String,
    val place: String,
    val timeDate: String,
    val email: String,
)

@Serializable
data class BookingResponse(
    val failure: String? = null
)

