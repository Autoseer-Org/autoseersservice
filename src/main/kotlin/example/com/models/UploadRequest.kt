package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class UploadRequest(
    val image: ByteArray,
)

@Serializable
data class UploadResponse(
    val failure: String? = null,
)
