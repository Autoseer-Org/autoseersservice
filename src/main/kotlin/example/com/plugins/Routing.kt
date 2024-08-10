package example.com.plugins

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import example.com.models.*
import example.com.services.GeminiServiceImpl
import example.com.services.RecallServiceImpl
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
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration

fun Application.configureRouting() {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val verificationTokenService = VerificationTokenServiceImpl(auth)
    val geminiService = GeminiServiceImpl()
    val recallService = RecallServiceImpl()

    val firestore: Firestore = FirestoreClient.getFirestore()
    routing {
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
                                        updatedDate = (it.data["updatedDate"] ).toString(),
                                        status = it.data["status"] as String,
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
                                        updatedDate = (it.data["updatedDate"] as Timestamp).toString(),
                                        status = it.data["status"] as String,
                                    )
                                    geminiService.generateAlertSummary(alert = part).collect { geminiResponse ->
                                        part.summary = geminiResponse?.summary ?: ""
                                    }
                                    alertsResponse.data?.add(part)
                                    alertsResponse.data?.add(part)
                                }
                                call.respond(HttpStatusCode.OK, alertsResponse)
                            }
                        } catch (e: Exception) {
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
                                        val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                                        if (carInfoRef?.get()?.get()?.data == null) {
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
                                        } else {
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
                                    ManualEntryResponse(failure = e.localizedMessage))
                            }
                        }
                    }
                }
        }

        get("/recalls") {
            val context = this.coroutineContext
            val authorizationToken = call.request.headers[HttpHeaders.Authorization]
            if (authorizationToken == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing Authorization token")
                return@get
            }
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = authorizationToken, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallsResponse(failure="Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallsResponse(failure="\"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(

                                    HttpStatusCode.BadRequest,
                                    RecallsResponse(failure="Failed to receive recall info")
                                )
                            }
                            call.respond(HttpStatusCode.BadRequest, RecallsResponse(failure="Failed to receive recall info"))
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
                                        HttpStatusCode.BadRequest,
                                        UploadResponse(failure = "No user found"))
                                }
                                val carInfoRef = userData?.get("carInfoRef") as? DocumentReference
                                val carInfo = withContext(Dispatchers.IO + context) {
                                    carInfoRef?.get()?.get()
                                }
                                val carInfoData = carInfo?.data
                                if (carInfoData == null) {
                                    call.respond(HttpStatusCode.BadRequest, RecallsResponse(failure="Incomplete car info"))
                                    return@collectLatest
                                }
                                val year = carInfoData?.get("year") as? Int
                                val make = carInfoData?.get("make") as? String
                                val model = carInfoData?.get("model") as? String
                                if (make.isNullOrBlank() || model.isNullOrBlank() || year == null) {
                                    call.respond(HttpStatusCode.BadRequest, RecallsResponse(failure="Incomplete car info"))
                                    return@collectLatest
                                }
                                recallService
                                    .queryRecallStatus(year, make, model)
                                    .collectLatest { publicRecallResponse ->
                                        val recallsSubcollectionRef = carInfoRef?.collection("recalls")
                                        if (recallsSubcollectionRef == null) {
                                            call.respond(HttpStatusCode.OK, RecallsResponse(0))
                                            return@collectLatest
                                        }
                                        val numRecallsStored = recallsSubcollectionRef?.get()?.get()?.size()
                                        val campaignNumbers = HashSet<String>()
                                        val querySnapshot = recallsSubcollectionRef?.get()
                                        var recallsResponse = RecallsResponse(numRecallsStored)
                                        if (publicRecallResponse == null) {
                                            recallsResponse = recallsResponse.copy(
                                                count = numRecallsStored,
                                                recalls = querySnapshot?.get()?.documents?.map { document ->
                                                    RecallItem(
                                                        shortSummary = document.get("shortSummary").toString(),
                                                        nhtsaCampaignNumber = document.get("nhtsaCampaignNumber").toString(),
                                                        manufacturer = document.get("manufacturer").toString(),
                                                        reportReceivedDate = document.get("reportReceivedDate").toString(),
                                                        component = document.get("component").toString(),
                                                        summary = document.get("summary").toString(),
                                                        consequence = document.get("consequence").toString(),
                                                        remedy = document.get("remedy").toString(),
                                                        notes = document.get("notes").toString(),
                                                        status = document.get("status").toString(),
                                                    )
                                                },
                                            )
                                            call.respond(HttpStatusCode.OK, recallsResponse)
                                            return@collectLatest
                                        }
                                        querySnapshot?.get()?.documents?.forEach { document ->
                                            val campaignNumber = document.get("nhtsaCampaignNumber") as? String ?: ""
                                            campaignNumbers.add(campaignNumber)
                                        }
                                        if (numRecallsStored != publicRecallResponse.count) {
                                            recallService.removeDuplicateRecallItems(publicRecallResponse, campaignNumbers)
                                            geminiService
                                                .generateShortSummariesForRecalls(publicRecallResponse)
                                                .collectLatest { geminiRecallShortSummaryData ->
                                                    geminiRecallShortSummaryData?.recallsItems?.forEachIndexed { idx, recallShortSummaryItem ->
                                                        val recallRef = recallsSubcollectionRef?.document()
                                                        val publicRecallObjectData = publicRecallResponse.results[idx]
                                                        recallRef?.set(mapOf(
                                                            "shortSummary" to recallShortSummaryItem.title,
                                                            "nhtsaCampaignNumber" to publicRecallObjectData.nhtsaCampaignNumber,
                                                            "manufacturer" to publicRecallObjectData.manufacturer,
                                                            "reportReceivedDate" to publicRecallObjectData.reportReceivedDate,
                                                            "component" to publicRecallObjectData.component,
                                                            "summary" to publicRecallObjectData.summary,
                                                            "consequence" to publicRecallObjectData.consequence,
                                                            "remedy" to publicRecallObjectData.remedy,
                                                            "notes" to publicRecallObjectData.notes,
                                                            "status" to "INCOMPLETE"
                                                        ))
                                                    }
                                                }
                                        }
                                        recallsResponse = recallsResponse.copy(
                                            count = numRecallsStored,
                                            recalls = querySnapshot?.get()?.documents?.map { document ->
                                                RecallItem(
                                                    shortSummary = document.get("shortSummary").toString(),
                                                    nhtsaCampaignNumber = document.get("nhtsaCampaignNumber").toString(),
                                                    manufacturer = document.get("manufacturer").toString(),
                                                    reportReceivedDate = document.get("reportReceivedDate").toString(),
                                                    component = document.get("component").toString(),
                                                    summary = document.get("summary").toString(),
                                                    consequence = document.get("consequence").toString(),
                                                    remedy = document.get("remedy").toString(),
                                                    notes = document.get("notes").toString(),
                                                    status = document.get("status").toString(),
                                                )
                                            },
                                        )
                                        call.respond(HttpStatusCode.OK, recallsResponse)
                                    }

                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    HomeResponse(failure = e.localizedMessage))
                            }
                        }
                    }
                }
        }

        post("/completeRecall") {
            val context = this.coroutineContext
            val request = call.receive<RecallItemUpdateRequest>()
            val token = request.token
            verificationTokenService
                .verifyAndCheckForTokenRevoked(token = token, getFirebaseToken = true)
                .collectLatest { verificationState ->
                    when (verificationState) {
                        is VerificationState.VerificationStateFailure -> {
                            verificationState.error?.let { errorState ->
                                if (errorState == VerificationErrorState.TokenRevoked) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallItemUpdateResonse("Authorization token has expired")
                                )
                                if (errorState == VerificationErrorState.MissingToken) call.respond(
                                    HttpStatusCode.Unauthorized,
                                    RecallItemUpdateResonse("\"Missing authorization token\"")
                                )
                                if (errorState == VerificationErrorState.FailedToParseToken) call.respond(
                                    HttpStatusCode.BadRequest,
                                    RecallItemUpdateResonse("Failed to update recall")
                                )
                            }
                            call.respond(
                                HttpStatusCode.BadRequest,
                                RecallItemUpdateResonse("Failed to update recall")
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
                                        ManualEntryResponse("User does not exist")
                                    )
                                }
                                val carInfoRef = userData?.get("carInfoRef") as DocumentReference
                                if (carInfoRef.get().get().data == null) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ManualEntryResponse("Car info does not exist")
                                    )
                                }
                                val recallsItemRef = carInfoRef?.collection("recalls")?.whereEqualTo("nhtsaCamapaignNumber", request.nhtsaCampaignNumber)?.get() as DocumentReference
                                if (recallsItemRef.get().get().data == null) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ManualEntryResponse("Recall item does not exist")
                                    )
                                }
                                recallsItemRef.update(mapOf(
                                    "status" to "COMPLETE",
                                ))
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
