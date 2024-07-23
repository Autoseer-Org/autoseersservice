package example.com.services

import com.google.firebase.auth.FirebaseAuth

object AuthService {
    fun verifyFirebaseToken(token: String): String? {
        return try {
            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            decodedToken.uid
        } catch (e: Exception) {
            null
        }
    }
}