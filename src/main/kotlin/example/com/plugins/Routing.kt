package example.com.plugins

import com.google.cloud.firestore.DocumentChange
import com.google.cloud.firestore.Precondition
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.cloud.FirestoreClient
import example.com.models.CreateUserProfileRequest
import example.com.models.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit

object TimeOut {
    const val value = 10L
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/createUserProfile") {
            val request = call.receive<CreateUserProfileRequest>()
            val token = request.token
            if (token.isNotBlank()) {
                try {
                    val uid = FirebaseAuth.getInstance().verifyIdToken(token).uid

                    val user = User(request.name)
                    val firestore = FirestoreClient.getFirestore()

                    val userObj: Map<String, Any> = hashMapOf(
                        "name" to request.name,
                    )
                    firestore
                        .collection("users")
                        .document(uid)
                        .set(userObj)
                        .get(TimeOut.value, TimeUnit.SECONDS)

                    call.respond(HttpStatusCode.Created, "User created: ${user.name}")
                } catch (e: FirebaseAuthException) {
                    call.respond(HttpStatusCode.Unauthorized, "Authorization token is invalid")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Failed to create user: $e")
                }
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Missing authorization token")
            }
        }
    }
}
