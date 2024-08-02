package example.com.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HomeRequest(val token: String)

@Serializable
data class HomeResponse(
    val data: HomeData? = null,
    val failure: String? = null
)

@Serializable
data class HomeData(
    @SerialName("mileage")
    val mileage: Int? = 0,
    @SerialName("health_score")
    val healthScore: Int? = 0,
    @SerialName("alert")
    val alerts: Int? = 0,
    @SerialName("repairs")
    val repairs: Int? = 0,
    @SerialName("reports")
    val reports: Int? = 0,
    @SerialName("make")
    val make: String? = null,
    @SerialName("model")
    val model: String? = null,
)