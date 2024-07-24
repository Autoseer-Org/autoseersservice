package example.com.plugins

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import example.com.models.CreateUserProfileRequest
import example.com.models.CreateUserProfileResponse
import example.com.services.VerificationErrorState
import example.com.services.VerificationState
import example.com.services.VerificationTokenServiceImpl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object TimeOut {
    const val VALUE = 10L
}

fun Application.configureRouting() {
    val auth = FirebaseAuth.getInstance()
    val verificationTokenService = VerificationTokenServiceImpl(auth)
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/createUserProfile") {
            val request = call.receive<CreateUserProfileRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CreateUserProfileResponse("Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CreateUserProfileResponse("\"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(
                                    HttpStatusCode.BadRequest,
                                    CreateUserProfileResponse("Failed to create user")
                                )
                            }
                            call.respond(HttpStatusCode.BadRequest, CreateUserProfileResponse("Failed to create user"))
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            val firestore = FirestoreClient.getFirestore()
                            val userObj: Map<String, Any> = hashMapOf(
                                "name" to request.name,
                            )

                            try {
                                withContext(Dispatchers.IO) {
                                    firestore
                                        .collection("users")
                                        .document(
                                            verificationState.firebaseToken?.uid
                                                ?: ""
                                        )
                                        .set(userObj)
                                    call.respond(HttpStatusCode.Created, CreateUserProfileResponse())
                                }
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError, CreateUserProfileResponse(
                                        failure = e.localizedMessage
                                    )
                                )
                            }
                        }
                    }
                }
        }
    }
}
