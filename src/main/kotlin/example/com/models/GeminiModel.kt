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

@Serializable
data class GeminiFinalRequest(val contents: List<Content>, val generationConfig: GenerationConfig)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(val text: String? = null, val fileData: FileData? = null)


@Serializable
data class FileData(val mimeType: String, val fileUri: String)

@Serializable
data class GenerationConfig(val responseMimeType: String)