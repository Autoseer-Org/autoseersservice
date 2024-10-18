package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class DeleteAccountModel(
    val success: Boolean,
)