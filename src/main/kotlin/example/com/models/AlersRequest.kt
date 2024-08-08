package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class AlertsRequest(
    val token: String
)

@Serializable
data class AlertsResponse(
    val data: MutableList<Alert>? = null
)

@Serializable
data class Alert(
    val name: String,
    val category: String,
    val updatedDate: String,
    val status: String,
    var summary: String? = "",
    var possibleFixes: String? = ""
)