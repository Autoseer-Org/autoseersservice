package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class UploadRequest(
    val token: String,
    val image: ByteArray,
)

@Serializable
data class UploadResponse(
    val failure: String? = null,
)
