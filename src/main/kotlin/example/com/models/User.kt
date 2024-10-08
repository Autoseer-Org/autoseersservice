package example.com.models

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val name: String
)

@Serializable
data class CreateUserProfileRequest(val name: String)

@Serializable
data class CreateUserProfileResponse(val failure: String? = null)