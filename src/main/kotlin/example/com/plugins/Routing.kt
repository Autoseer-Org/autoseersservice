package example.com.plugins

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import example.com.models.*
import example.com.services.GeminiServiceImpl
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
import java.sql.Timestamp

object TimeOut {
    const val VALUE = 10L
}

fun Application.configureRouting() {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val verificationTokenService = VerificationTokenServiceImpl(auth)
    val geminiService = GeminiServiceImpl()

    val firestore: Firestore = FirestoreClient.getFirestore()
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/home") {
            val context = this.coroutineContext
            val request = call.receive<HomeRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collect { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        val userDoc = firestore
                            .collection("users")
                            .document(uid)
                            try {
                                var homeResponse = HomeResponse()
                                val userData = withContext(Dispatchers.IO + context) {
                                    userDoc.get().get()
                                }.data
                                if (userData == null) {
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                }
                                val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                                val carInfo = withContext(Dispatchers.IO + context) {
                                    carInfoRef.get().get()
                                }
                                if (carInfo?.data?.isEmpty() == true) {
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                } else {
                                    homeResponse = homeResponse.copy(
                                        data = HomeData(
                                            mileage = carInfo.data?.get("mileage").toString().toIntOrNull() ?: 0,
                                            healthScore = carInfo.data?.get("carHealth").toString().toIntOrNull() ?: 0,
                                            model = carInfo.data?.get("model").toString(),
                                            make = carInfo.data?.get("make").toString(),
                                        ),
                                    )
                                    val mediumParts = carInfo
                                        .reference
                                        .collection("carPartsStatus")
                                        .whereEqualTo("status", "Medium")
                                        .get()

                                    val mediumPartRef =
                                        withContext(Dispatchers.IO + context) {
                                            mediumParts.get()
                                        }.size()
                                    homeResponse = homeResponse.copy(homeResponse.data?.copy(alerts = mediumPartRef))

                                    val badParts = carInfo
                                        .reference
                                        .collection("carPartsStatus")
                                        .whereEqualTo("status", "Bad")
                                        .get()
                                    val badPartsRef =
                                        withContext(Dispatchers.IO + context) {
                                            badParts.get()
                                        }.size()
                                    homeResponse = homeResponse.copy(
                                        homeResponse.data?.copy(
                                            alerts = homeResponse.data?.alerts
                                                ?: (0 + badPartsRef)
                                        )
                                    )
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                }
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = e.localizedMessage))
                            }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            CreateUserProfileResponse("Authorization token not valid"))
                    }
                }
        }
        post("/upload") {
            val request = call.receive<UploadRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = false)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val image = request.image
                        geminiService
                            .generateCarParts(image)
                            .collectLatest { carReportData ->
                                try {
                                    if (carReportData != null) {
                                        val carInfoRef = firestore.collection("carInfo")
                                            .document()
                                        carInfoRef.set(mapOf(
                                            "make" to carReportData.carMake,
                                            "mileage" to carReportData.carMileage,
                                            "model" to carReportData.carModel,
                                            "year" to carReportData.carYear,
                                            "carHealth" to carReportData.healthScore,
                                        ))
                                        carReportData.parts.forEach { part ->
                                            carInfoRef
                                                .collection("carPartsStatus")
                                                .document()
                                                .set(mapOf(
                                                    "category" to part.category,
                                                    "name" to part.partName,
                                                    "status" to part.status,
                                                    "updatedDate" to Timestamp(System.currentTimeMillis())
                                                ))
                                        }
                                    }
                                    call.respond(HttpStatusCode.OK, UploadResponse())
                                    // Store carInfoReference and set it to the user's doc
                                    // Store Gemini API key in GCP!
                                }catch(e: Exception) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        UploadResponse(failure = e.localizedMessage))
                                }
                            }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            CreateUserProfileResponse("Authorization token not valid"))
                    }
                }
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
                            val userObj: Map<String, Any> = hashMapOf(
                                "name" to request.name,
                            )

                            try {
                                withContext(Dispatchers.IO) {
                                    val userDocRef = firestore
                                        .collection("users")
                                        .document(
                                            verificationState.firebaseToken?.uid
                                                ?: ""
                                        )
                                    val userDoc = userDocRef.get().get()
                                    if (userDoc.exists()) {
                                        call.respond(HttpStatusCode.Created, CreateUserProfileResponse())
                                    } else {
                                        userDocRef.set(userObj)
                                    }

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
