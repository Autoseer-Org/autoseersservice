package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class PollBookingStatusRequest(
    val partId: String,
)

@Serializable
data class PollBookingStatusResponse(
    val state: BookingState,
    val failure: String? = null
)

enum class BookingState {
    NO_BOOKING_REQUESTED,
    WAITING_TO_BE_BOOKED,
    PROCESSING,
    BOOKED,
    CANCELLED;

    companion object {
        fun fromString(str: String) = entries.firstOrNull { it.name == str } ?: NO_BOOKING_REQUESTED
    }
}