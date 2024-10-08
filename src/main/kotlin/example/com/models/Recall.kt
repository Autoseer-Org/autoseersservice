package example.com.models


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
    @SerialName("nhtsa_campaign_number")
    val nhtsaCampaignNumber: String,
    val manufacturer:  String,
    @SerialName("report_received_date")
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
    @SerialName("parkIt")
    val parkIt: Boolean,
    @SerialName("parkOutSide")
    val parkOutSide: Boolean,
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
    @SerialName("ModelYear")
    val modelYear: String,
    @SerialName("Make")
    val make: String,
    @SerialName("Model")
    val model: String,
)

@Serializable
data class CompleteRecallRequest(
    @SerialName("nhtsa_campaign_number")
    val nhtsaCampaignNumber: String,
)

@Serializable
data class CompleteRecallResponse(
    val failure: String? = null,
)