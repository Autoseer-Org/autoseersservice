package example.com.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeminiReportData(
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