package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationsRequest(
    val token: String,
)

@Serializable
data class RecommendationsResponse(
    val error: String? = null,
    val data: GeminiRecommendationModel? = null
)



