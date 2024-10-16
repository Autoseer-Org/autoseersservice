package example.com.plugins

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import example.com.models.*
import example.com.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.apache.commons.logging.Log
import java.sql.Timestamp

fun Application.configureRouting() {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val verificationTokenService = VerificationTokenServiceImpl(auth)
    val geminiService = GeminiServiceImpl()
    val recallService = RecallServiceImpl()

    val firestore: Firestore = FirestoreClient.getFirestore()
    routing {
        get("/deleteAccount") {
            getAuthRequestToken()?.let { token ->
                verificationTokenService
                    .deleteAccount(token)
                    .flowOn(Dispatchers.IO)
                    .onEmpty { call.respond(HttpStatusCode.BadRequest, DeleteAccountModel(success = false)) }
                    .catch {
                        logError(call, it)
                        call.respond(HttpStatusCode.ExpectationFailed, DeleteAccountModel(success = false))
                    }
                    .collectLatest { verificationState ->
                        val uid = verificationState.uid.ifBlank { return@collectLatest }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData?.get("carInfoRef") == "") {
                                null
                            } else {
                                userData?.get("carInfoRef") as DocumentReference
                            }?.delete()
                            userDoc.delete()
                            call.respond(HttpStatusCode.OK, DeleteAccountModel(success = true))
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(HttpStatusCode.OK, DeleteAccountModel(success = false))
                        }
                    }
            }
        }
        get("/recommendations") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to fetch recommendations: Missing or invalid authorization")
                )
                return@get
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            verificationTokenService.verifyAndCheckForTokenRevoked(token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            val carInfoRef = if (userData?.get("carInfoRef") == "") {
                                null
                            } else {
                                userData?.get("carInfoRef") as DocumentReference
                            }
                            if (carInfoRef == null) {
                                call.respond(
                                    HttpStatusCode.OK, RecommendationsResponse(
                                        error = "NEEDS_CAR_INFO"
                                    )
                                )
                                return@collectLatest
                            } else {
                                val carInfoData = carInfoRef.get().get().data
                                val carMake = carInfoData?.get("make") as? String
                                val carModel = carInfoData?.get("model") as? String
                                var mileage = carInfoData?.get("mileage") as? String
                                val year = carInfoData?.get("year") as? String
                                if (carMake.isNullOrBlank() || carModel.isNullOrEmpty() || mileage.isNullOrBlank() || year.isNullOrBlank()) {
                                    call.respond(
                                        HttpStatusCode.OK, RecommendationsResponse(
                                            error = "NEEDS_CAR_INFO"
                                        )
                                    )
                                    return@collectLatest
                                } else {
                                    geminiService.generateRecommendedServices(
                                        CarInfoModel(
                                            make = carMake,
                                            model = carModel,
                                            mileage = mileage,
                                            year = year
                                        )
                                    ).collectLatest { geminiRecommendations ->
                                        if (geminiRecommendations == null) {
                                            call.respond(
                                                HttpStatusCode.InternalServerError,
                                                RecommendationsResponse(
                                                    error = "Failure to fetch recommendations: Failed to produce response from gemini"
                                                )
                                            )
                                            return@collectLatest
                                        } else {
                                            call.respond(
                                                HttpStatusCode.OK, RecommendationsResponse(
                                                    data = geminiRecommendations
                                                )
                                            )
                                            return@collectLatest
                                        }
                                    }
                                }

                            }
                        } catch (e: Exception) {
                            logError(call = call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError, RecommendationsResponse(
                                    error = "Failure to fetch recommendations: ${e.localizedMessage}"
                                )
                            )
                            return@collectLatest
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            RecommendationsResponse(error = "Failure to fetch recommendations: Authorization token not valid")
                        )
                        return@collectLatest
                    }
                }
        }

        post("/pollBookingStatus") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to fetch booking status: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<PollBookingStatusRequest>()
            verificationTokenService.verifyAndCheckForTokenRevoked(token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest, PollBookingStatusResponse(
                                    BookingState.CANCELLED,
                                    failure = "Failure to fetch booking status: User profile not found"
                                )
                            )
                            return@collectLatest
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest, PollBookingStatusResponse(
                                        BookingState.CANCELLED,
                                        failure = "" +
                                                "Failure to fetch booking status: User profile not found"
                                    )
                                )
                                return@collectLatest
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData["carInfoRef"] == "") {
                                call.respond(
                                    HttpStatusCode.BadRequest, PollBookingStatusResponse(
                                        BookingState.CANCELLED,
                                        failure = "Failure to fetch booking status: Car data not found"
                                    )
                                )
                                return@collectLatest
                            }

                            val carPartScheduledServiceRef = carInfoRef
                                ?.collection("carPartScheduledService")
                                ?.document(request.partId)

                            val carPartScheduledServiceData =
                                carPartScheduledServiceRef?.get()?.get()?.data
                            if (carPartScheduledServiceData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.OK,
                                    PollBookingStatusResponse(state = BookingState.NO_BOOKING_REQUESTED)
                                )
                                return@collectLatest
                            }
                            call.respond(
                                HttpStatusCode.OK, PollBookingStatusResponse(
                                    state = BookingState
                                        .fromString(
                                            carPartScheduledServiceData
                                                ?.get("bookingState")
                                                .toString()
                                        ),
                                )
                            )
                            return@collectLatest
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                PollBookingStatusResponse(
                                    state = BookingState.CANCELLED,
                                    failure = "Failure to fetch booking status: ${e.localizedMessage}"
                                )
                            )
                            return@collectLatest
                        }
                    }
                }
        }
        post("/markAsRepaired") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to mark part as repaired: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<MarkAsRepairedRequest>()
            verificationTokenService.verifyAndCheckForTokenRevoked(token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.InternalServerError, BookingResponse(
                                    failure = "Failure to mark part as repaired: User profile not found"
                                )
                            )
                            return@collectLatest
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.InternalServerError, MarkAsRepairedResponse(
                                        failure = "Failure to mark part as repaired: User profile not found"
                                    )
                                )
                                return@collectLatest
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData?.get("carInfoRef") == "") {
                                call.respond(
                                    HttpStatusCode.InternalServerError, MarkAsRepairedResponse(
                                        failure = "Failure to mark part as repaired: Car info not found"
                                    )
                                )
                                return@collectLatest
                            }
                            userDoc.update(
                                mapOf(
                                    "repairs" to (userData?.get("repairs") as Long).plus(1),
                                )
                            )
                            val carPartStatusRef = carInfoRef
                                ?.collection("carPartsStatus")
                                ?.document(request.partId)

                            val carPartStatusData = carPartStatusRef?.get()?.get()?.data
                            if (carPartStatusData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.OK, MarkAsRepairedResponse()
                                )
                                return@collectLatest
                            }
                            carPartStatusRef?.update(
                                mapOf(
                                    "status" to "Good"
                                )
                            )
                            val carInfo = withContext(Dispatchers.IO + context) {
                                carInfoRef.get().get()
                            }
                            val allParts = carInfo
                                .reference
                                .collection("carPartsStatus")
                                .get()
                            val allPartsRef = allParts.get()
                            val allPartsSize = allPartsRef.size()
                            val goodParts = carInfo
                                .reference
                                .collection("carPartsStatus")
                                .whereEqualTo("status", "Good")
                                .get()
                            val goodPartsRef = goodParts.get()
                            val goodPartsSize = goodPartsRef.size()
                            val newHealthScore: Int = (100 * goodPartsSize) / allPartsSize
                            carInfoRef?.update(
                                mapOf(
                                    "carHealth" to newHealthScore
                                )
                            )
                            call.respond(
                                HttpStatusCode.OK, MarkAsRepairedResponse()
                            )
                            return@collectLatest
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                MarkAsRepairedResponse(failure = "Failure to mark part as repaired: ${e.localizedMessage}")
                            )
                            return@collectLatest
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            MarkAsRepairedResponse(failure = "Failure to mark part as repaired: Authorization token not valid")
                        )
                        return@collectLatest
                    }
                }
        }
        post("/bookAppointment") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to book appointment: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<BookingRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.InternalServerError, BookingResponse(
                                    failure = "Failure to book appointment: User profile not found"
                                )
                            )
                            return@collectLatest
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.InternalServerError, BookingResponse(
                                        failure = "Failure to book appointment: User profile not found"
                                    )
                                )
                                return@collectLatest
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData?.get("carInfoRef") == "") {
                                call.respond(
                                    HttpStatusCode.InternalServerError, BookingResponse(
                                        failure = "Failure to book appointment: Car data not found"
                                    )
                                )
                                return@collectLatest
                            }
                            val carPartStatusRef = carInfoRef
                                ?.collection("carPartsStatus")
                                ?.document(request.id)

                            carInfoRef
                                ?.collection("carPartScheduledService")
                                ?.document(request.id)
                                ?.set(
                                    mapOf(
                                        "place" to request.place,
                                        "hasBeenBooked" to false,
                                        "bookingState" to BookingState.WAITING_TO_BE_BOOKED,
                                        "carPartStatusRef" to carPartStatusRef,
                                        "scheduledFor" to request.timeDate,
                                        "email" to request.email
                                    )
                                )
                            call.respond(
                                HttpStatusCode.OK, BookingResponse()
                            )
                            return@collectLatest
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                BookingResponse(failure = "Failure to book appointment: ${e.localizedMessage}")
                            )
                            return@collectLatest
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            BookingResponse(failure = "Failure to book appointment: Authorization token not valid")
                        )
                        return@collectLatest
                    }
                }
        }
        get("/") {
            call.respondText("Hello World!")
        }
        get("/alerts") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to fetch alerts: Missing or invalid authorization")
                )
                return@get
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collect { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        val userDoc = firestore
                            .collection("users")
                            .document(uid)
                        try {
                            var alertsResponse = AlertsResponse()
                            val userData = withContext(Dispatchers.IO + context) {
                                userDoc.get().get()
                            }.data
                            if (userData == null) {
                                call.respond(HttpStatusCode.OK, alertsResponse)
                                return@collect
                            }
                            val carInfoRef = userData["carInfoRef"] as DocumentReference
                            val carInfo = withContext(Dispatchers.IO + context) {
                                carInfoRef.get().get()
                            }
                            if (carInfo?.data?.isEmpty() == true) {
                                call.respond(HttpStatusCode.OK, alertsResponse)
                                return@collect
                            } else {
                                alertsResponse = alertsResponse.copy(data = mutableListOf())
                                val mediumParts = carInfo
                                    .reference
                                    .collection("carPartsStatus")
                                    .whereEqualTo("status", "Medium")
                                    .get()

                                val mediumPartRef = mediumParts.get()

                                mediumPartRef.forEach {
                                    val partDoc = carInfo
                                        .reference
                                        .collection("carPartsStatus")
                                        .document(it.id)

                                    val description =
                                        partDoc.get().get().data?.get("description").toString()
                                            .takeIf { desc -> desc.isBlank().not() }
                                    val part = Alert(
                                        name = it.data["name"] as String,
                                        category = it.data["category"] as String,
                                        updatedDate = (it.data["updatedDate"]).toString(),
                                        status = it.data["status"] as String,
                                        id = it.id
                                    )
                                    if (description.isNullOrEmpty() || description == "null") {
                                        geminiService.generateAlertSummary(alert = part)
                                            .collect { geminiResponse ->
                                                part.summary = geminiResponse?.summary ?: ""
                                            }
                                        partDoc.update(
                                            mapOf(
                                                "description" to part.summary
                                            )
                                        )
                                    } else {
                                        part.summary = description
                                    }
                                    alertsResponse.data?.add(part)
                                }

                                val badParts = carInfo
                                    .reference
                                    .collection("carPartsStatus")
                                    .whereEqualTo("status", "Bad")
                                    .get()
                                val badPartsRef = badParts.get()
                                badPartsRef.forEach {
                                    val partDoc = carInfo
                                        .reference
                                        .collection("carPartsStatus")
                                        .document(it.id)
                                    val description =
                                        partDoc.get().get().data?.get("description").toString()
                                            .takeIf { desc -> desc.isBlank().not() }
                                    val part = Alert(
                                        name = it.data["name"] as String,
                                        category = it.data["category"] as String,
                                        updatedDate = (it.data["updatedDate"]).toString(),
                                        status = it.data["status"] as String,
                                        id = it.id
                                    )
                                    if (description.isNullOrEmpty() || description == "null") {
                                        geminiService.generateAlertSummary(alert = part)
                                            .collect { geminiResponse ->
                                                part.summary = geminiResponse?.summary ?: ""
                                            }
                                        partDoc.update(
                                            mapOf(
                                                "description" to part.summary
                                            )
                                        )
                                    } else {
                                        part.summary = description
                                    }
                                    alertsResponse.data?.add(part)
                                }
                                call.respond(HttpStatusCode.OK, alertsResponse)
                            }
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                AlertsResponse(failure = "Failure to fetch alerts: ${e.localizedMessage}")
                            )
                            return@collect
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            AlertsResponse(failure = "Failure to fetch alerts: Authorization token not valid")
                        )
                        return@collect
                    }
                }
        }
        get("/home") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to fetch home page data: Missing or invalid authorization")
                )
                return@get
            }
            val token = authHeader.removePrefix("Bearer ").trim()
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
                            val userData = userDoc.get().get().data
                            if (userData == null) {
                                call.respond(HttpStatusCode.OK, homeResponse)
                                return@collect
                            }
                            val carInfoRef = if (userData?.get("carInfoRef") == "") {
                                null
                            } else {
                                userData?.get("carInfoRef") as? DocumentReference
                            }
                            val carInfo = carInfoRef?.get()?.get()
                            if (carInfo?.exists() == false || carInfoRef == null) {
                                call.respond(HttpStatusCode.OK, homeResponse)
                                return@collect
                            } else {
                                homeResponse = homeResponse.copy(
                                    data = HomeData(
                                        mileage = carInfo?.data?.get("mileage") as String? ?: "",
                                        healthScore = carInfo?.data?.get("carHealth").toString()
                                            .toIntOrNull() ?: 0,
                                        model = carInfo?.data?.get("model").toString(),
                                        make = carInfo?.data?.get("make").toString(),
                                        recalls = carInfo?.data?.get("recalls").toString()
                                            .toIntOrNull(),
                                        repairs = (userData["repairs"] as Long).toInt(),
                                        userName = userData["name"] as String,
                                        estimatedCarPrice = carInfo?.data?.get("estimatedCarPrice") as String? ?: "",
                                    ),
                                )
                                val mediumParts = carInfo
                                    ?.reference
                                    ?.collection("carPartsStatus")
                                    ?.whereEqualTo("status", "Medium")
                                    ?.get()

                                val mediumPartSize =
                                    withContext(Dispatchers.IO + context) {
                                        mediumParts?.get()
                                    }?.size() ?: 0
                                val badParts = carInfo
                                    ?.reference
                                    ?.collection("carPartsStatus")
                                    ?.whereEqualTo("status", "Bad")
                                    ?.get()
                                val badPartsSize =
                                    withContext(Dispatchers.IO + context) {
                                        badParts?.get()
                                    }?.size() ?: 0
                                val totalAlertsSize = badPartsSize + mediumPartSize
                                homeResponse =
                                    homeResponse.copy(homeResponse.data?.copy(alerts = totalAlertsSize))
                                call.respond(HttpStatusCode.OK, homeResponse)
                                return@collect
                            }
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                HomeResponse(failure = "Failure to fetch home page data: ${e.localizedMessage}")
                            )
                            return@collect
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            HomeResponse(failure = "Failure to fetch home page data: Authorization token not valid")
                        )
                        return@collect
                    }
                }
        }
        post("/upload") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to upload report: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<UploadRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val image = request.image
                        geminiService
                            .generateCarInfoFromImage(image)
                            .collectLatest { carReportData ->
                                val uid = verificationStatus.firebaseToken?.uid ?: ""
                                if (carReportData?.isImageValid == false || carReportData == null || uid.isBlank()) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        UploadResponse(failure = "Failure to upload report: Invalid report image")
                                    )
                                } else {
                                    try {
                                        val userDoc = firestore
                                            .collection("users")
                                            .document(uid)
                                        val userData = userDoc.get().get().data
                                        if (userData == null) {
                                            call.respond(
                                                HttpStatusCode.InternalServerError,
                                                UploadResponse(failure = "Failure to upload report: No user found")
                                            )
                                        }
                                        val carInfoRef = if (userData?.get("carInfoRef") == "") {
                                            null
                                        } else {
                                            userData?.get("carInfoRef") as DocumentReference
                                        }
                                        val carInfoRefData = carInfoRef?.get()?.get()?.data
                                        userDoc.update(
                                            mapOf(
                                                "uploads" to FieldValue.increment(1),
                                            )
                                        )
                                        if (carInfoRefData.isNullOrEmpty()) {
                                            val newCarInfoRef = firestore.collection("carInfo")
                                                .document()
                                            userDoc.update(
                                                mapOf(
                                                    "carInfoRef" to newCarInfoRef,
                                                )
                                            )
                                            val recalls: Int? =
                                                if (carReportData.carMake.isNotBlank() && carReportData.carModel.isNotBlank() && carReportData.carYear.isNotBlank()) {
                                                    0
                                                } else {
                                                    null
                                                }
                                            newCarInfoRef.set(
                                                mapOf(
                                                    "make" to carReportData.carMake,
                                                    "mileage" to carReportData.carMileage,
                                                    "model" to carReportData.carModel,
                                                    "year" to carReportData.carYear,
                                                    "carHealth" to carReportData.healthScore,
                                                    "recalls" to recalls,
                                                )
                                            )
                                            carReportData.parts.forEach { part ->
                                                newCarInfoRef
                                                    .collection("carPartsStatus")
                                                    .document()
                                                    .set(
                                                        mapOf(
                                                            "category" to part.category,
                                                            "name" to part.partName,
                                                            "status" to part.status,
                                                            "updatedDate" to Timestamp(System.currentTimeMillis())
                                                        )
                                                    )
                                            }
                                        } else {
                                            if (carInfoRefData["make"].toString().isNotBlank()
                                                || carInfoRefData["model"].toString()
                                                    .isNotBlank()
                                                || carInfoRefData["year"].toString()
                                                    .isNotBlank()
                                            ) {
                                                carInfoRef.update(
                                                    mapOf(
                                                        "make" to carReportData.carMake,
                                                        "mileage" to carReportData.carMileage,
                                                        "model" to carReportData.carModel,
                                                        "year" to carReportData.carYear,
                                                        "carHealth" to carReportData.healthScore,
                                                    )
                                                )
                                            }
                                            carInfoRef.update(
                                                mapOf(
                                                    "carHealth" to carReportData.healthScore,
                                                )
                                            )
                                            val docs = carInfoRef
                                                .collection("carPartsStatus")
                                                .get()
                                                .get()
                                                .documents
                                            for (doc in docs) {
                                                carInfoRef.collection("carPartsStatus")
                                                    .document(doc.id).delete()
                                            }
                                            carReportData.parts.forEach { part ->
                                                carInfoRef
                                                    .collection("carPartsStatus")
                                                    .document()
                                                    .set(
                                                        mapOf(
                                                            "category" to part.category,
                                                            "name" to part.partName,
                                                            "status" to part.status,
                                                            "updatedDate" to Timestamp(System.currentTimeMillis())
                                                        )
                                                    )
                                            }
                                        }
                                        call.respond(HttpStatusCode.OK, UploadResponse())
                                    } catch (e: Exception) {
                                        logError(call, e)
                                        call.respond(
                                            HttpStatusCode.InternalServerError,
                                            UploadResponse(failure = "Failure to upload report: ${e.localizedMessage}")
                                        )
                                    }
                                }
                            }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            CreateUserProfileResponse("Failure to upload report: Authorization token not valid")
                        )
                    }
                }
        }
        post("/createUserProfile") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to create user profile: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<CreateUserProfileRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CreateUserProfileResponse("Failure to create user profile: Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CreateUserProfileResponse("Failure to create user profile: \"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(
                                    HttpStatusCode.InternalServerError,
                                    CreateUserProfileResponse("Failed to create user profile")
                                )
                            }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                CreateUserProfileResponse("Failure to create user profile")
                            )
                            return@collectLatest
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            val userObj: Map<String, Any> = hashMapOf(
                                "name" to request.name,
                                "carInfoRef" to "",
                                "repairs" to 0,
                                "uploads" to 0,
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
                                        call.respond(
                                            HttpStatusCode.Created,
                                            CreateUserProfileResponse()
                                        )
                                        return@withContext
                                    } else {
                                        userDocRef.set(userObj)
                                    }

                                    call.respond(
                                        HttpStatusCode.Created,
                                        CreateUserProfileResponse()
                                    )
                                    return@withContext
                                }
                            } catch (e: Exception) {
                                logError(call, e)
                                call.respond(
                                    HttpStatusCode.InternalServerError, CreateUserProfileResponse(
                                        failure = "Failure to create user profile: ${e.localizedMessage}"
                                    )
                                )
                                return@collectLatest
                            }
                        }
                        is VerificationState.AccountDeleted -> TODO()
                    }
                }
        }

        post("/manualEntry") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to manually enter card data: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<ManualEntryRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ManualEntryResponse("Failure to manually enter card data: Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ManualEntryResponse("Failure to manually enter card data: \"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(

                                    HttpStatusCode.InternalServerError,
                                    ManualEntryResponse("Failure to manually enter card data: Failed to enter car info")
                                )
                            }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ManualEntryResponse("Failure to manually enter card data: Failed to enter car info")
                            )
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            try {
                                val uid = verificationState.firebaseToken?.uid ?: ""
                                val userDoc = firestore
                                    .collection("users")
                                    .document(uid)
                                val userData = withContext(Dispatchers.IO + context) {
                                    userDoc.get().get()
                                }.data
                                if (userData == null) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ManualEntryResponse("Failure to manually enter card data: User could not be found")
                                    )
                                }
                                val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                                val carInfoData = withContext(Dispatchers.IO + context) {
                                    carInfoRef.get().get()
                                }.data
                                val make = request.make.ifBlank {
                                    carInfoData?.get("make") ?: ""
                                }
                                val mileage = request.mileage?.ifBlank {
                                    carInfoData?.get("mileage") ?: ""
                                }
                                val model = request.model.ifBlank {
                                    carInfoData?.get("model") ?: ""
                                }
                                val year = request.year.ifBlank {
                                    carInfoData?.get("year") ?: ""
                                }
                                val recalls: Int? = if (make.toString().isNotBlank()
                                    && model.toString().isNotBlank()
                                    && year.toString().isNotBlank()
                                ) {
                                    0
                                } else {
                                    null
                                }
                                carInfoRef.set(
                                    mapOf(
                                        "make" to make,
                                        "model" to model,
                                        "year" to year,
                                        "mileage" to mileage,
                                        "carHealth" to (carInfoData?.get("carHealth") ?: ""),
                                        "recalls" to recalls
                                    )
                                )
                                call.respond(HttpStatusCode.OK, ManualEntryResponse())
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ManualEntryResponse(failure = "Failure to manually enter card data: ${e.localizedMessage}")
                                )
                            }
                        }

                        is VerificationState.AccountDeleted -> TODO()
                    }
                }
        }

        get("/recalls") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to fetch open recalls: Missing or invalid authorization")
                )
                return@get
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallsResponse(failure = "Failure to fetch open recalls: Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallsResponse(failure = "Failure to fetch open recalls: \"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(

                                    HttpStatusCode.InternalServerError,
                                    RecallsResponse(failure = "Failure to fetch open recalls: Failed to fetch recall info")
                                )
                            }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                RecallsResponse(failure = "Failure to fetch open recalls: Failed to fetch recall info")
                            )
                            return@collectLatest
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            try {
                                val uid = verificationState.firebaseToken?.uid ?: ""
                                val userDoc = firestore
                                    .collection("users")
                                    .document(uid)
                                val userData = userDoc.get().get().data
                                if (userData == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        UploadResponse(failure = "Failure to fetch open recalls: No user found")
                                    )
                                    return@collectLatest
                                }
                                val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                                val carInfo = withContext(Dispatchers.IO + context) {
                                    carInfoRef?.get()?.get()
                                }
                                val carInfoData = carInfo?.data
                                if (carInfoData == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        RecallsResponse(failure = "Failure to fetch open recalls: Incomplete car info")
                                    )
                                    return@collectLatest
                                }
                                val year = carInfoData?.get("year") as? String
                                val make = carInfoData?.get("make") as? String
                                val model = carInfoData?.get("model") as? String
                                if (make.isNullOrBlank() || model.isNullOrBlank() || year.isNullOrBlank()) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        RecallsResponse(failure = "Failure to fetch open recalls: Incomplete car info")
                                    )
                                    return@collectLatest
                                }
                                recallService
                                    .queryRecallStatus(year, make, model)
                                    .collectLatest { publicRecallResponse ->
                                        try {
                                            val recallsSubcollectionRef =
                                                carInfoRef?.collection("recalls")
                                            if (recallsSubcollectionRef == null) {
                                                call.respond(HttpStatusCode.OK, RecallsResponse(0))
                                                return@collectLatest
                                            }
                                            val numRecallsStored =
                                                recallsSubcollectionRef?.get()?.get()?.size() ?: 0
                                            val campaignNumbers = HashSet<String>()
                                            val querySnapshot = recallsSubcollectionRef?.get()
                                            var recallsResponse = RecallsResponse(numRecallsStored)
                                            if (publicRecallResponse == null) {
                                                recallsResponse = recallsResponse.copy(
                                                    count = numRecallsStored,
                                                    recalls = querySnapshot?.get()?.documents?.map { document ->
                                                        RecallItem(
                                                            nhtsaCampaignNumber = document.get("nhtsaCampaignNumber")
                                                                .toString(),
                                                            manufacturer = document.get("manufacturer")
                                                                .toString(),
                                                            reportReceivedDate = document.get("reportReceivedDate")
                                                                .toString(),
                                                            component = document.get("component")
                                                                .toString(),
                                                            summary = document.get("summary")
                                                                .toString(),
                                                            consequence = document.get("consequence")
                                                                .toString(),
                                                            remedy = document.get("remedy")
                                                                .toString(),
                                                            notes = document.get("notes")
                                                                .toString(),
                                                            status = document.get("status")
                                                                .toString(),
                                                        )
                                                    },
                                                )
                                                val recallsAmount =
                                                    recallsResponse.recalls?.size ?: 0
                                                carInfoRef.update(
                                                    mapOf(
                                                        "recalls" to recallsAmount
                                                    )
                                                )
                                                call.respond(HttpStatusCode.OK, recallsResponse)
                                                return@collectLatest
                                            }
                                            querySnapshot?.get()?.documents?.forEach { document ->
                                                val campaignNumber =
                                                    document.get("nhtsaCampaignNumber") as? String
                                                        ?: ""
                                                campaignNumbers.add(campaignNumber)
                                            }
                                            if (numRecallsStored <= publicRecallResponse.count) {
                                                recallService.removeDuplicateRecallItems(
                                                    publicRecallResponse,
                                                    campaignNumbers
                                                )
                                                publicRecallResponse.results.forEach { publicRecallObject ->
                                                    val recallRef =
                                                        recallsSubcollectionRef?.document()
                                                    recallRef?.set(
                                                        mapOf(
                                                            "nhtsaCampaignNumber" to publicRecallObject.nhtsaCampaignNumber,
                                                            "manufacturer" to publicRecallObject.manufacturer,
                                                            "reportReceivedDate" to publicRecallObject.reportReceivedDate,
                                                            "component" to publicRecallObject.component,
                                                            "summary" to publicRecallObject.summary,
                                                            "consequence" to publicRecallObject.consequence,
                                                            "remedy" to publicRecallObject.remedy,
                                                            "notes" to publicRecallObject.notes,
                                                            "status" to "INCOMPLETE"
                                                        )
                                                    )
                                                }
                                            }
                                            recallsResponse = recallsResponse.copy(
                                                count = numRecallsStored,
                                                recalls = querySnapshot?.get()?.documents?.map { document ->
                                                    RecallItem(
                                                        nhtsaCampaignNumber = document.get("nhtsaCampaignNumber")
                                                            .toString(),
                                                        manufacturer = document.get("manufacturer")
                                                            .toString(),
                                                        reportReceivedDate = document.get("reportReceivedDate")
                                                            .toString(),
                                                        component = document.get("component")
                                                            .toString(),
                                                        summary = document.get("summary")
                                                            .toString(),
                                                        consequence = document.get("consequence")
                                                            .toString(),
                                                        remedy = document.get("remedy").toString(),
                                                        notes = document.get("notes").toString(),
                                                        status = document.get("status").toString(),
                                                    )
                                                },
                                            )
                                            val recallsAmount = recallsResponse.recalls?.size ?: 0
                                            carInfoRef.update(
                                                mapOf(
                                                    "recalls" to recallsAmount
                                                )
                                            )
                                            call.respond(HttpStatusCode.OK, recallsResponse)
                                            return@collectLatest
                                        } catch (e: Exception) {
                                            call.respond(
                                                HttpStatusCode.InternalServerError,
                                                HomeResponse(failure = "Failure to fetch open recalls: ${e.localizedMessage}")
                                            )
                                            return@collectLatest
                                        }
                                    }

                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = "Failure to fetch open recalls: ${e.localizedMessage}")
                                )
                                return@collectLatest
                            }
                        }
                        is VerificationState.AccountDeleted -> TODO()
                    }
                }
        }

        post("/completeRecall") {
            val context = this.coroutineContext
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to complete open recall: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val request = call.receive<CompleteRecallRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CompleteRecallResponse("Failure to complete open recall: Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    CompleteRecallResponse("Failure to complete open recall: \"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(
                                    HttpStatusCode.InternalServerError,
                                    CompleteRecallResponse("Failure to complete open recall")
                                )
                            }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                CompleteRecallResponse("Failure to complete open recall")
                            )
                            return@collectLatest
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            try {
                                val uid = verificationState.firebaseToken?.uid ?: ""
                                val userDoc = firestore
                                    .collection("users")
                                    .document(uid)
                                val userData = withContext(Dispatchers.IO + context) {
                                    userDoc.get().get()
                                }.data
                                if (userData == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        CompleteRecallResponse("Failure to complete open recall: User not found")
                                    )
                                    return@collectLatest
                                }
                                val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                                if (carInfoRef.get().get().data == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        CompleteRecallResponse("Failure to complete open recall: Car info not found")
                                    )
                                    return@collectLatest
                                }
                                val filteredRecalls = carInfoRef
                                    ?.collection("recalls")
                                    ?.whereEqualTo(
                                        "nhtsaCampaignNumber",
                                        request.nhtsaCampaignNumber
                                    )
                                    ?.limit(1)
                                    ?.get()
                                if (filteredRecalls == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        CompleteRecallResponse("Failure to complete open recall: Recall not found")
                                    )
                                    return@collectLatest
                                }
                                val recallItemRef =
                                    filteredRecalls.get()?.documents?.get(0)?.reference
                                if (recallItemRef?.get()?.get()?.data == null) {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        CompleteRecallResponse("Failure to complete open recall: Recall not found")
                                    )
                                    return@collectLatest
                                }
                                recallItemRef?.update(
                                    mapOf(
                                        "status" to "COMPLETE",
                                    )
                                )
                                call.respond(
                                    HttpStatusCode.OK, CompleteRecallResponse()
                                )
                                return@collectLatest
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    CompleteRecallResponse(failure = "Failure to complete open recall: ${e.localizedMessage}")
                                )
                                return@collectLatest
                            }
                        }

                        is VerificationState.AccountDeleted -> TODO()
                    }
                }
        }

        post("/revokeTokens") {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    HomeResponse(failure = "Failure to revoke tokens: Missing or invalid authorization")
                )
                return@post
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    HomeResponse(failure = "Failure to revoke tokens: Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    HomeResponse(failure = "Failure to revoke tokens: \"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = "Failure to revoke tokens")
                                )
                            }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                HomeResponse(failure = "Failure to revoke tokens")
                            )
                            return@collectLatest
                        }

                        is VerificationState.VerificationStateSuccess -> {
                            try {
                                val uid = verificationState.firebaseToken?.uid ?: ""
                                FirebaseAuth.getInstance().revokeRefreshTokens(uid)
                                call.respond(
                                    HttpStatusCode.OK, HomeResponse()
                                )
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = "Failure to revoke tokens: ${e.localizedMessage}")
                                )
                            }
                        }

                        is VerificationState.AccountDeleted -> TODO()
                    }
                }
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.getAuthRequestToken(): String? {
    val authHeader = call.request.headers["Authorization"]
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respond(HttpStatusCode.Unauthorized)
        return null
    }
    val token = authHeader.removePrefix("Bearer ").trim()
    return token
}
