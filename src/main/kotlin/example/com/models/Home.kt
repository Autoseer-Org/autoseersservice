package example.com.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HomeResponse(
    val data: HomeData? = null,
    val failure: String? = null
)

@Serializable
data class HomeData(
    @SerialName("mileage")
    val mileage: String? = "",
    @SerialName("health_score")
    val healthScore: Int? = 0,
    @SerialName("alerts")
    val alerts: Int? = 0,
    @SerialName("recalls")
    val recalls: Int? = 0,
    @SerialName("repairs")
    val repairs: Int? = 0,
    @SerialName("reports")
    val reports: Int? = 0,
    @SerialName("make")
    val make: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("estimatedCarPrice")
    val estimatedCarPrice: String? = "",
    @SerialName("userName")
    val userName: String? = "",
)