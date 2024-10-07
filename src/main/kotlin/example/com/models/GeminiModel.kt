package example.com.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeminiReportData(
    @SerialName("is_image_valid")
    val isImageValid: Boolean,
    @SerialName("car_make")
    val carMake: String,
    @SerialName("car_model")
    val carModel: String,
    @SerialName("car_year")
    val carYear: String,
    @SerialName("mileage")
    val carMileage: String,
    @SerialName("carHealthScore")
    val healthScore: String,
    @SerialName("carParts")
    val parts: List<GeminiPartData>,
    @SerialName("estimatedCarPrice")
    val estimatedCarPrice: String?
)

@Serializable
data class GeminiPartData(
    @SerialName("category")
    val category: String,
    @SerialName("part")
    val partName: String,
    @SerialName("status")
    val status: String,
)

@Serializable
data class GeminiRecallShortSummaryData(
    val recallsItems: List<GeminiRecallItem>
)

@Serializable
data class GeminiRecallItem(
    val title: String
)