package example.com.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeminiSummaryModel(
    @SerialName("summary")
    val summary: String,
)