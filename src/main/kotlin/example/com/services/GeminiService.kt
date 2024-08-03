package example.com.services

import example.com.models.GeminiReportData
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.util.*

interface GeminiService {
    suspend fun generateCarPartsFromImage(image: ByteArray): Flow<GeminiReportData?>
    fun generateRepairPlaces()
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
                   Finally, you should also add a field to the json for the total mileage of the car if it's found. If it's not found just fill it with an empty string. The json field name should be mileage. One more thing! Check if the image is a valid report of a car. If not, create a json field called is_image_valid that will be either true or false"},
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
        val apiKey = System.getenv("gemini_api_key") ?: "AIzaSyBTev3sqMNmGiv60XBz8Nsi0u9C5ED_2h0"
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

    override fun generateRepairPlaces() {
        TODO("Not yet implemented")
    }

}