package example.com.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecallsResponse(
    val count: Int? = null,
    var recalls: List<RecallItem>? = null,
    val failure: String? = null
)

@Serializable
data class RecallItem(
    val shortSummary: String,
    val nhtsaCampaignNumber: String,
    val manufacturer:  String,
    val reportReceivedDate: String,
    val component: String,
    val summary: String,
    val consequence: String,
    val remedy: String,
    val notes: String,
    val status: String
)

@Serializable
data class PublicRecallResponse(
    @SerialName("Count")
    var count:  Int,
    @SerialName("Message")
    val message: String,
    var results: MutableList<PublicRecallObjectData>
)

@Serializable
data class PublicRecallObjectData(
    @SerialName("Manufacturer")
    val manufacturer:  String,
    @SerialName("NHTSACampaignNumber")
    val nhtsaCampaignNumber: String,
    @SerialName("ReportReceivedDate")
    val reportReceivedDate: String,
    @SerialName("Component")
    val component: String,
    @SerialName("Summary")
    val summary: String,
    @SerialName("Consequence")
    val consequence: String,
    @SerialName("Remedy")
    val remedy: String,
    @SerialName("Notes")
    val notes: String,
)

@Serializable
data class CompleteRecallRequest(
    val token: String,
    val nhtsaCampaignNumber: String,
)

@Serializable
data class CompleteRecallResponse(
    val failure: String? = null,
)