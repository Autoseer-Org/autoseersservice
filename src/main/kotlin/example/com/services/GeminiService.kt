package example.com.services

import example.com.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.*

interface GeminiService {
    suspend fun generateCarPartsFromImage(image: ByteArray): Flow<GeminiReportData?>
    suspend fun generateRecommendedServices(carInfoModel: CarInfoModel): Flow<GeminiRecommendationModel?>
    suspend fun generateAlertSummary(alert: Alert): Flow<GeminiSummaryModel?>
    suspend fun generateShortSummariesForRecalls(publicRecallResponse: PublicRecallResponse): Flow<GeminiRecallShortSummaryData?>
}

class GeminiServiceImpl : GeminiService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
    }

    private fun geminiApiKey(): String {
        return System.getenv("gemini_api_key") ?: "AIzaSyAzpEsvJ8OOCIReSFTo9u573r0KlC2p5yU"
    }

    override suspend fun generateCarPartsFromImage(image: ByteArray): Flow<GeminiReportData?> = flow {
        val based64Image = Base64.getEncoder().encodeToString(image)
        val requestBody = """
        {
          "contents":[
            {
              "parts":[
                {"text": "You're the CarSeer! The most knowledgeable system for car reports and check point inspections for cars!
                 I need you to create a json response with all the parts that have been found here in this report. 
                 The parts have been marked with an \"X\" or a \"Mark\" on them to represent their status: Good, Medium and Bad (the json fields should be part, status, category). 
                 The idea is that we can generate a collection of car parts that are as generic as possible such that other multi checkpoint reports from other companies can easily be parsed and generate the same json response.
                  We care about the category that the part belongs too such as interior, exterior, etc. We also care about the make and model of the car as well as the year (json fields should be car_make, car_model, car_year and for the parts it should be carParts). 
                  If car make, model, and year were missing then fill them with empty strings. Lastly, you should look at the parts that need attention and the parts that are good and create overall health score for the car. it should range from 0 to 100 since it should a percentage value and the json field name should be carHealthScore (string).
                   Finally, you should also add a field to the json for the total mileage of the car if it's found. If it's not found just fill it with an empty string. The json field name should be mileage. One more thing! Check if the image is a valid report of a car. If not, create a json field called is_image_valid that will be false else true if the car image is valid! You should looks for car reports like car inspections specifically"},
                {
                  "inline_data": {
                    "mime_type":"image/jpeg",
                    "data": "$based64Image" 
                  }
                }
              ]
            }
          ],
          "generationConfig": {
                "stopSequences": [
                    "Title"
                ],
                "temperature": 1.0,
                "responseMimeType": "application/json"
          }
        }
    """.trimIndent()
        val apiKey = geminiApiKey()
       try {
            val response =
                client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey") {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    setBody(requestBody)
                }
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
            val extractedText = candidates.mapNotNull { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
           println(extractedText)
            emit(Json.decodeFromString<GeminiReportData>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }

    override suspend fun generateRecommendedServices(carInfoModel: CarInfoModel): Flow<GeminiRecommendationModel?> = flow {
        val requestBody = """
        {
          "contents":[
            {
              "parts":[
                {"text": "
                    I am CarSeer, a car service recommendation system. I can provide you with up to 8 personalized maintenance recommendations based on your car's make, model, year, and current mileage.
                    To get your recommendations, please tell me:
                    Make: (e.g., Honda, Toyota, Ford). The current make is ${carInfoModel.make}
                    Model: (e.g., Civic, Camry, F-150). The current model is ${carInfoModel.model}
                    Year: (e.g., 2018, 2022). The current year is ${carInfoModel.year}
                    Mileage: (e.g., 30000, 55000). The current mileage is ${carInfoModel.mileage}
                    I will then generate a JSON response that includes the following information for each recommended service:
                    serviceName: The name of the service (e.g., Oil Change, Tire Rotation)
                    averagePrice: An estimated average cost in USD. An example would be $10.00
                    description: A brief description of the service, tailored to your specific car model
                    frequency: The general service interval (e.g., Every 30,000 miles)
                    priority: A number from 1 to 10, indicating the urgency of the service, with 10 being the most urgent. This value should be driven by the mileage the car has and the model of the car! 
                    It's very important to be consistent with the result! 
                "},
              ]
            }
          ],
          "generationConfig": {
                "stopSequences": [
                    "Title"
                ],
                "temperature": 1.0,
                "responseMimeType": "application/json"
          }
        }
    """.trimIndent()
        val apiKey = geminiApiKey()
        try {
            val response =
                client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey") {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    setBody(requestBody)
                }
            println(response.bodyAsText())
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
            val extractedText = candidates.mapNotNull { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
            println(extractedText)
            emit(Json.decodeFromString<GeminiRecommendationModel>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }

    override suspend fun generateAlertSummary(alert: Alert): Flow<GeminiSummaryModel?> = flow {
        val requestBody = """
        {
          "contents":[
            {
              "parts":[
                {"text": "You're the CarSeer! The most knowledgeable system for car reports and check point inspections for cars!
                 Your job is to create a summary for a cart part that's been found to be in a ${alert.status} state and detail possible 
                 things that could have happened in the first place that caused the part to be in this state. The part name is ${alert.name} and the category is ${alert.category}.
                 You can store this summary in a field called summary. You should also keep it short but very informative (very important)!"
                 },
              ]
            }
          ],
          "generationConfig": {
                "stopSequences": [
                    "Title"
                ],
                "temperature": 1.0,
                "responseMimeType": "application/json"
          }
        }
    """.trimIndent()
        val apiKey = geminiApiKey()
        try {
            val response =
                client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey") {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    setBody(requestBody)
                }
            println(response.bodyAsText())
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
            val extractedText = candidates.mapNotNull { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
            println(extractedText)
            emit(Json.decodeFromString<GeminiSummaryModel>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }

    override suspend fun generateShortSummariesForRecalls(publicRecallResponse: PublicRecallResponse): Flow<GeminiRecallShortSummaryData?> = flow {
        val json = Json { encodeDefaults = true }
        val jsonResponse = json.encodeToString<PublicRecallResponse>(publicRecallResponse)
        val requestBody = """
        {
          "contents":[
            {
              "parts":[
                {"text": "I have a json below and I need you to return a json of list of recall 
                items. I want you to read through each item in results list and give me a brief 
                summary of the situation in each recall item. I need to create cards for a mobile 
                app with just a title summary of the situation. Please make sure that you return the 
                list in the same order as before.
                 $jsonResponse"
                 },
              ]
            }
          ],
          "generationConfig": {
                "stopSequences": [
                    "Title"
                ],
                "temperature": 1.0,
                "responseMimeType": "application/json"
          }
        }
    """.trimIndent()
        val apiKey = geminiApiKey()
        try {
            val response =
                client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey") {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    setBody(requestBody)
                }
            println(response.bodyAsText())
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
            val extractedText = candidates.mapNotNull { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
            println(extractedText)
            emit(Json.decodeFromString<GeminiRecallShortSummaryData>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }

}