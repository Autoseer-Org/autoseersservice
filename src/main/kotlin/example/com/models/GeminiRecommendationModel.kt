package example.com.models

import kotlinx.serialization.Serializable

data class CarInfoModel(
    val make: String,
    val model: String,
    val year: String,
    val mileage: String,
)

@Serializable
data class GeminiRecommendationModel(
    val recommendations: List<Recommendation>
)

@Serializable
data class Recommendation(
    val serviceName: String,
    val averagePrice: String,
    val description: String,
    val frequency: String,
    val priority: Int,
)