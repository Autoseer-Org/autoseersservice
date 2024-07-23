package example.com.services

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.WriteResult
import com.google.firebase.cloud.FirestoreClient
import example.com.models.User

object UserService {
    suspend fun createUserProfile(uid: String, user: User) {
        val db = FirestoreClient.getFirestore()
        val userRef = db.collection("users").document(uid)

        // Convert User data class to a HashMap
        val userData = hashMapOf(
            "name" to user.name // Map the User properties to fields
        )

        val writeResult: WriteResult = try {
            // Perform the write operation
            val writeFuture: ApiFuture<WriteResult> = userRef.set(userData as Map<String, Any>)
            writeFuture.get()
        } catch (e: Exception) {
            throw e
        }

        // Access the write result
        println("Document ID: ${writeResult.updateTime}")
    }

    fun userExists(uid: String): Boolean {
        // Implement the function to check if a user exists in your database
        return true // For demonstration purposes
    }
}