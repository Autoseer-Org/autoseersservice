package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class MarkAsRepairedRequest(
    val token: String,
    val partId: String,
)

@Serializable
data class MarkAsRepairedResponse(
    val failure: String? = null
)