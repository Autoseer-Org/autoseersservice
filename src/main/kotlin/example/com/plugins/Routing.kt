package example.com.plugins

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
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
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.sql.Timestamp

fun Application.configureRouting() {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val verificationTokenService = VerificationTokenServiceImpl(auth)
    val geminiService = GeminiServiceImpl()

    val firestore: Firestore = FirestoreClient.getFirestore()
    routing {
        post("/recommendations") {
            val request = call.receive<RecommendationsRequest>()
            verificationTokenService.verifyAndCheckForTokenRevoked(request.token, true)
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
                            } else {
                                val carInfoData = carInfoRef.get().get().data
                                val carMake = carInfoData?.get("make") as String
                                val carModel = carInfoData["model"] as String
                                var mileage = carInfoData["mileage"] as String
                                val year = carInfoData["year"] as String
                                if (carMake.isEmpty() || carModel.isEmpty() || mileage.isBlank() || year.isBlank()) {
                                    call.respond(
                                        HttpStatusCode.OK, RecommendationsResponse(
                                            error = "NEEDS_CAR_INFO"
                                        )
                                    )
                                } else {
                                    geminiService.generateRecommendedServices(
                                        CarInfoModel(make = carMake, model = carModel, mileage = mileage, year = year)
                                    ).collectLatest { geminiRecommendations ->
                                        if (geminiRecommendations == null) {
                                            call.respond(
                                                HttpStatusCode.OK, RecommendationsResponse(
                                                    error = "Failed to produce response from gemini"
                                                )
                                            )
                                        }else {
                                            call.respond(
                                                HttpStatusCode.OK, RecommendationsResponse(
                                                    data = geminiRecommendations
                                                )
                                            )
                                        }
                                    }
                                }

                            }
                        } catch (e: Exception) {
                            logError(call = call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError, RecommendationsResponse(
                                    error = "Failed to produce response"
                                )
                            )
                        }
                    }
                }
        }

        post("/pollBookingStatus") {
            val request = call.receive<PollBookingStatusRequest>()
            verificationTokenService.verifyAndCheckForTokenRevoked(request.token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.OK, PollBookingStatusResponse(
                                    BookingState.CANCELLED,
                                    failure = "Could not process request. Try again later"
                                )
                            )
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest, PollBookingStatusResponse(
                                        BookingState.CANCELLED,
                                        failure = "" +
                                                "Could not process request. Try again later"
                                    )
                                )
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData["carInfoRef"] == "") {
                                call.respond(
                                    HttpStatusCode.InternalServerError, MarkAsRepairedResponse(
                                        failure = "Could not process request. Try again later"
                                    )
                                )
                            }

                            val carPartScheduledServiceRef = carInfoRef
                                ?.collection("carPartScheduledService")
                                ?.document(request.partId)

                            val carPartScheduledServiceData = carPartScheduledServiceRef?.get()?.get()?.data
                            if (carPartScheduledServiceData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.OK, PollBookingStatusResponse(state = BookingState.NO_BOOKING_REQUESTED)
                                )
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
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                MarkAsRepairedResponse(failure = e.localizedMessage)
                            )
                        }
                    }
                }
        }
        post("/markAsRepaired") {
            val request = call.receive<MarkAsRepairedRequest>()
            verificationTokenService.verifyAndCheckForTokenRevoked(request.token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.OK, BookingResponse(
                                    failure = "" +
                                            "Could not process request. Try again later"
                                )
                            )
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest, MarkAsRepairedResponse(
                                        failure = "" +
                                                "Could not process request. Try again later"
                                    )
                                )
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData?.get("carInfoRef") == "") {
                                call.respond(
                                    HttpStatusCode.InternalServerError, MarkAsRepairedResponse(
                                        failure = "" +
                                                "Could not process request. Try again later"
                                    )
                                )
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
                                    HttpStatusCode.OK, BookingResponse()
                                )
                            }
                            carPartStatusRef?.update(mapOf(
                                "status" to "Good"
                            ))
                            call.respond(
                                HttpStatusCode.OK, MarkAsRepairedResponse()
                            )
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                MarkAsRepairedResponse(failure = e.localizedMessage)
                            )
                        }
                    }
                }
        }
        post("/bookAppointment") {
            val request = call.receive<BookingRequest>()
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = request.token, true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val uid = verificationStatus.firebaseToken?.uid ?: ""
                        if (uid.isBlank()) {
                            call.respond(
                                HttpStatusCode.OK, BookingResponse(
                                    failure = "" +
                                            "Could not process booking. Try again later"
                                )
                            )
                        }
                        val userDoc = firestore.collection("users").document(uid)
                        try {
                            val userData = userDoc.get().get().data
                            if (userData.isNullOrEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest, BookingResponse(
                                        failure = "" +
                                                "Could not process booking. Try again later"
                                    )
                                )
                            }
                            val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                            val carInfoData = carInfoRef?.get()?.get()?.data
                            if (carInfoData.isNullOrEmpty() || userData?.get("carInfoRef") == "") {
                                call.respond(
                                    HttpStatusCode.InternalServerError, BookingResponse(
                                        failure = "" +
                                                "Could not process booking. Try again later"
                                    )
                                )
                            }
                            val carPartStatusRef = carInfoRef
                                ?.collection("carPartsStatus")
                                ?.document(request.id)

                            carInfoRef
                                ?.collection("carPartScheduledService")
                                ?.document(request.id)
                                ?.set(mapOf(
                                    "place" to request.place,
                                    "hasBeenBooked" to false,
                                    "bookingState" to BookingState.WAITING_TO_BE_BOOKED,
                                    "carPartStatusRef" to carPartStatusRef,
                                    "scheduledFor" to request.timeDate,
                                    "email" to request.email
                                ))
                            call.respond(
                                HttpStatusCode.OK, BookingResponse()
                            )
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                BookingResponse(failure = e.localizedMessage)
                            )
                        }
                    }
                }
        }
        get("/") {
            call.respondText("Hello World!")
        }
        post("/alerts") {
            val context = this.coroutineContext
            val request = call.receive<AlertsRequest>()
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
                            var alertsResponse = AlertsResponse()
                            val userData = withContext(Dispatchers.IO + context) {
                                userDoc.get().get()
                            }.data
                            if (userData == null) {
                                call.respond(HttpStatusCode.OK, alertsResponse)
                            }
                            val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                            val carInfo = withContext(Dispatchers.IO + context) {
                                carInfoRef.get().get()
                            }
                            if (carInfo?.data?.isEmpty() == true) {
                                call.respond(HttpStatusCode.OK, alertsResponse)
                            } else {
                                alertsResponse = alertsResponse.copy(data = mutableListOf())
                                val mediumParts = carInfo
                                    .reference
                                    .collection("carPartsStatus")
                                    .whereEqualTo("status", "Medium")
                                    .get()

                                val mediumPartRef = mediumParts.get()

                                mediumPartRef.forEach {
                                    val part = Alert(
                                        name = it.data["name"] as String,
                                        category = it.data["category"] as String,
                                        updatedDate = (it.data["updatedDate"]).toString(),
                                        status = it.data["status"] as String,
                                        id = it.id
                                    )
                                    geminiService.generateAlertSummary(alert = part).collect { geminiResponse ->
                                        part.summary = geminiResponse?.summary ?: ""
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
                                    val part = Alert(
                                        name = it.data["name"] as String,
                                        category = it.data["category"] as String,
                                        updatedDate = (it.data["updatedDate"]).toString(),
                                        status = it.data["status"] as String,
                                        id = it.id
                                    )
                                    geminiService.generateAlertSummary(alert = part).collect { geminiResponse ->
                                        part.summary = geminiResponse?.summary ?: ""
                                    }
                                    alertsResponse.data?.add(part)
                                }
                                call.respond(HttpStatusCode.OK, alertsResponse)
                            }
                        } catch (e: Exception) {
                            logError(call, e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                AlertsResponse()
                            )
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            AlertsResponse()
                        )
                    }
                }
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
                                val userData = userDoc.get().get().data
                                if (userData == null) {
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                }
                                val carInfoRef = if (userData?.get("carInfoRef") == "") {
                                    null
                                } else {
                                    userData?.get("carInfoRef") as? DocumentReference
                                }
                                val carInfo = carInfoRef?.get()?.get()
                                if (carInfo?.exists() == false || carInfoRef == null) {
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                } else {
                                    homeResponse = homeResponse.copy(
                                        data = HomeData(
                                            mileage = carInfo?.data?.get("mileage").toString().toIntOrNull() ?: 0,
                                            healthScore = carInfo?.data?.get("carHealth").toString().toIntOrNull() ?: 0,
                                            model = carInfo?.data?.get("model").toString(),
                                            make = carInfo?.data?.get("make").toString(),
                                            repairs = (userData?.get("repairs") as Long).toInt(),
                                            reports = (userData["uploads"] as Long).toInt(),
                                        ),
                                    )
                                    val mediumParts = carInfo
                                        ?.reference
                                        ?.collection("carPartsStatus")
                                        ?.whereEqualTo("status", "Medium")
                                        ?.get()

                                    val mediumPartRef =
                                        withContext(Dispatchers.IO + context) {
                                            mediumParts?.get()
                                        }?.size()
                                    homeResponse = homeResponse.copy(homeResponse.data?.copy(alerts = mediumPartRef))

                                    val badParts = carInfo
                                        ?.reference
                                        ?.collection("carPartsStatus")
                                        ?.whereEqualTo("status", "Bad")
                                        ?.get()
                                    val badPartsRef =
                                        withContext(Dispatchers.IO + context) {
                                            badParts?.get()
                                        }?.size()
                                    homeResponse = homeResponse.copy(
                                        homeResponse.data?.copy(
                                            alerts = (homeResponse.data?.alerts?.plus(badPartsRef ?: 0))
                                                ?: (0 + (badPartsRef ?: 0))
                                        )
                                    )
                                    call.respond(HttpStatusCode.OK, homeResponse)
                                }
                            } catch (e: Exception) {
                                logError(call, e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = e.localizedMessage))
                            }
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            HomeResponse())
                    }
                }
        }
        post("/upload") {
            val request = call.receive<UploadRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationStatus ->
                    if (verificationStatus is VerificationState.VerificationStateSuccess) {
                        val image = request.image
                        geminiService
                            .generateCarPartsFromImage(image)
                            .collectLatest { carReportData ->
                                val uid = verificationStatus.firebaseToken?.uid ?: ""
                                if (carReportData?.isImageValid == false) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        UploadResponse(failure = "invalid image"))
                                } else {
                                    try {
                                        val userDoc = firestore
                                            .collection("users")
                                            .document(uid)
                                        val userData = userDoc.get().get().data
                                        if (userData == null) {
                                            call.respond(
                                                HttpStatusCode.BadRequest,
                                                UploadResponse(failure = "No user found"))
                                        }
                                        val carInfoRef = if (userData?.get("carInfoRef") == "") {
                                            null
                                        } else {
                                            userData?.get("carInfoRef") as DocumentReference
                                        }
                                        val carInfoRefData = carInfoRef?.get()?.get()?.data
                                        if (carInfoRefData.isNullOrEmpty()) {
                                            if (carReportData != null) {
                                                val newCarInfoRef = firestore.collection("carInfo")
                                                    .document()
                                                userDoc.update(
                                                    mapOf(
                                                        "carInfoRef" to newCarInfoRef,
                                                        "uploads" to FieldValue.increment(1),
                                                    )
                                                )
                                                newCarInfoRef.set(mapOf(
                                                    "make" to carReportData.carMake,
                                                    "mileage" to carReportData.carMileage,
                                                    "model" to carReportData.carModel,
                                                    "year" to carReportData.carYear,
                                                    "carHealth" to carReportData.healthScore,
                                                ))
                                                carReportData.parts.forEach { part ->
                                                    newCarInfoRef
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
                                        } else {
                                            userDoc.update(
                                                mapOf(
                                                    "uploads" to FieldValue.increment(1),
                                                )
                                            )
                                            carInfoRef.update(
                                                mapOf(
                                                    "make" to carReportData?.carMake,
                                                    "mileage" to carReportData?.carMileage,
                                                    "model" to carReportData?.carModel,
                                                    "year" to carReportData?.carYear,
                                                    "carHealth" to carReportData?.healthScore,
                                            ))
                                            carReportData?.parts?.forEach { part ->
                                                carInfoRef
                                                    .collection("carPartsStatus")
                                                    .document()
                                                    .update(mapOf(
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
                                        logError(call, e)
                                        call.respond(
                                            HttpStatusCode.InternalServerError,
                                            UploadResponse(failure = e.localizedMessage))
                                    }
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
                                        call.respond(HttpStatusCode.Created, CreateUserProfileResponse())
                                    } else {
                                        userDocRef.set(userObj)
                                    }

                                    call.respond(HttpStatusCode.Created, CreateUserProfileResponse())
                                }
                            } catch (e: Exception) {
                                logError(call, e)
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

        post("/manualEntry") {
            val context = this.coroutineContext
            val request = call.receive<ManualEntryRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ManualEntryResponse("Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ManualEntryResponse("\"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(

                                    HttpStatusCode.BadRequest,
                                    ManualEntryResponse("Failed to enter car info")
                                )
                            }
                            call.respond(HttpStatusCode.BadRequest, ManualEntryResponse("Failed to enter car info"))
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
                                    call.respond(HttpStatusCode.BadRequest, ManualEntryResponse("User does not exist"))
                                }
                                val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                                val carInfoData = withContext(Dispatchers.IO + context) {
                                    carInfoRef.get().get()
                                }.data
                                val make = request.make.ifBlank {
                                    carInfoData?.get("make") ?: ""
                                }
                                val mileage = request.mileage.ifBlank {
                                    carInfoData?.get("mileage") ?: ""
                                }
                                val model = request.model.ifBlank {
                                    carInfoData?.get("model") ?: ""
                                }
                                val year = request.year.ifBlank {
                                    carInfoData?.get("year") ?: ""
                                }
                                carInfoRef.set(mapOf(
                                    "make" to make,
                                    "mileage" to mileage,
                                    "model" to model,
                                    "year" to year,
                                    "carHealth" to (carInfoData?.get("carHealth") ?: ""),
                                ))
                                call.respond(HttpStatusCode.OK, ManualEntryResponse())
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = e.localizedMessage))
                            }
                        }
                    }
                }
        }
    }
}
