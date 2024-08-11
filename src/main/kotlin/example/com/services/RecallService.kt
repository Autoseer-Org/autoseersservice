package example.com.services

import example.com.models.GeminiSummaryModel
import example.com.models.PublicRecallResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.append
import io.ktor.http.headers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.internal.NopCollector.emit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface RecallService {
    suspend fun queryRecallStatus(year: Int, make: String, model: String): Flow<PublicRecallResponse?>
    fun removeDuplicateRecallItems(publicRecallResponse: PublicRecallResponse, set: HashSet<String>)
}

class RecallServiceImpl: RecallService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
    }

    override suspend fun queryRecallStatus(year: Int, make: String, model: String): Flow<PublicRecallResponse?> = flow {
        val publicRecallUrl = "https://api.nhtsa.gov/recalls/recallsByVehicle?make=$make&model=$model&modelYear=$year"
        try {
            val response = client.get(publicRecallUrl) {
                headers { append(HttpHeaders.Accept, ContentType.Application.Json) }
            }
            println(response.bodyAsText())
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
            val extractedText = candidates.mapNotNull { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
            println(extractedText)
            emit(Json.decodeFromString<PublicRecallResponse>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }

    override fun removeDuplicateRecallItems(publicRecallResponse: PublicRecallResponse, set: HashSet<String>) {
        publicRecallResponse.results.removeAll {
            it.nhtsaCampaignNumber in set
        }
        publicRecallResponse.count = publicRecallResponse.results.size
    }
}