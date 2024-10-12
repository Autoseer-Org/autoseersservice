package example.com.services

import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun geminiApiKey(): String {
    return System.getenv("gemini_api_key") ?: ""
}

internal suspend fun getGeminiData(response: HttpResponse): List<String> {
    val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val candidates = jsonObject["candidates"]?.jsonArray ?: emptyList()
    val extractedText = candidates.mapNotNull { candidate ->
        candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.find { it.jsonObject["text"] != null }?.jsonObject?.get("text")?.jsonPrimitive?.content
    }
    return extractedText
}

