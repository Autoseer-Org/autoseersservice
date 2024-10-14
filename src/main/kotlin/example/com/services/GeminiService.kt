package example.com.services

import example.com.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

interface GeminiService {
    suspend fun generateCarInfoFromImage(image: ByteArray): Flow<GeminiReportData?>
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

    override suspend fun generateCarInfoFromImage(image: ByteArray): Flow<GeminiReportData?> = flow {
        val apiKey = geminiApiKey()
        val imageFile = File.createTempFile("image", ".jpg") // Create a temporary image file
        imageFile.writeBytes(image)
        try {
            // Optimize image vision process by storing all images files in GCloud.
            // We can use the URI provided by File API and use it to request gemini data
            val mimeType = ContentType.Image.JPEG.toString()
            val numBytes = imageFile.length()
            val displayName = "CarReportImage"
            val initialResponse: HttpResponse =
                client.post("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey") {
                    header("X-Goog-Upload-Protocol", "resumable")
                    header("X-Goog-Upload-Command", "start")
                    header("X-Goog-Upload-Header-Content-Length", numBytes.toString())
                    header("X-Goog-Upload-Header-Content-Type", mimeType)
                    contentType(ContentType.Application.Json)

                    setBody(buildJsonObject {
                        putJsonObject("file") {
                            put("display_name", displayName)
                        }

                    })

                }
            val uploadUrl = initialResponse.headers["X-Goog-Upload-URL"]

            if (uploadUrl == null) {
                println("Error getting upload URL: ${initialResponse.bodyAsText()}")
                emit(null)
                return@flow
            }
            val fileInfoResponse: HttpResponse = client.put(uploadUrl) {
                header("Content-Length", numBytes.toString())
                header("X-Goog-Upload-Offset", "0")
                header("X-Goog-Upload-Command", "upload, finalize")
                setBody(imageFile.readBytes())
            }

            val body = fileInfoResponse.bodyAsText()
            val fileUri = Json.decodeFromString<JsonObject>(body)
                .jsonObject["file"]?.jsonObject?.get("uri")?.jsonPrimitive?.content

            if (fileUri == null) {
                println("Error getting file URI")
                emit(null)
                return@flow
            }

            val prompt =
                """"You're the CarSeer! The most knowledgeable system for car reports and check point inspections for cars!
                 I need you to create a json response with all the parts that have been found here in this report. 
                 The parts have been marked with an \"X\" or a \"Mark\" on them to represent their status: Good, Medium and Bad (the json fields should be part, status, category). 
                 The idea is that we can generate a collection of car parts that are as generic as possible such that other multi checkpoint reports from other companies can easily be parsed and generate the same json response.
                  We care about the category that the part belongs too such as interior, exterior, etc. We also care about the make and model of the car as well as the year (json fields should be car_make, car_model, car_year and for the parts it should be carParts). 
                  If car make, model, and year were missing then fill them with empty strings. Lastly, you should look at the parts that need attention and the parts that are good and create overall health score for the car. it should range from 0 to 100 since it should a percentage value and the json field name should be carHealthScore (string).
                   Finally, you should also add a field to the json for the total mileage of the car if it's found (The json field should be mileage as a String). If it's not found then set it to an empty string. The json field name should be mileage. One more thing! Check if the image is a valid report of a car. If not, create a json field called is_image_valid that will be false else true if the car image is valid! You should looks for car reports like car inspections specifically"""".trimIndent()
            val geminiRequest = GeminiFinalRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = prompt),
                            Part(fileData = FileData(mimeType = mimeType, fileUri = fileUri))
                        )
                    ),
                ),
                generationConfig = GenerationConfig("application/json")
            )


            val geminiResponse: HttpResponse =
                client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey") {
                    contentType(ContentType.Application.Json)
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    setBody(geminiRequest)
                }
            val extractedText = getGeminiData(geminiResponse)
            emit(Json.decodeFromString<GeminiReportData>(extractedText[0]))
            imageFile.delete()
        } catch (e: ClientRequestException) {
            println("Network error: ${e.message}")
            emit(null)
        } catch (e: ServerResponseException) {
            println("Server error: ${e.response.status}")
            emit(null)
        } catch (e: Exception) {
            println("An unexpected error occurred: ${e.message}")
            emit(null)
        } finally {
            imageFile.delete()
        }
    }
        .flowOn(Dispatchers.IO)


    override suspend fun generateRecommendedServices(carInfoModel: CarInfoModel): Flow<GeminiRecommendationModel?> =
        flow {
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
                        contentType(ContentType.Application.Json)
                        headers {
                            append(HttpHeaders.Accept, ContentType.Application.Json)
                        }
                        setBody(requestBody)
                    }
                val extractedText = getGeminiData(response)
                emit(Json.decodeFromString<GeminiRecommendationModel>(extractedText[0]))
            } catch (e: Exception) {
                println("error: ${e.localizedMessage}")
                emit(null)
            }
        }.flowOn(Dispatchers.IO)

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
            val extractedText = getGeminiData(response)
            emit(Json.decodeFromString<GeminiSummaryModel>(extractedText[0]))
        } catch (e: Exception) {
            println("error: ${e.localizedMessage}")
            emit(null)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateShortSummariesForRecalls(publicRecallResponse: PublicRecallResponse): Flow<GeminiRecallShortSummaryData?> =
        flow {
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
                val extractedText = getGeminiData(response)
                emit(Json.decodeFromString<GeminiRecallShortSummaryData>(extractedText[0]))
            } catch (e: Exception) {
                println("error: ${e.localizedMessage}")
                emit(null)
            }
        }.flowOn(Dispatchers.IO)

}