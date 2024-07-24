package example.com

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.ktor.utils.io.core.*
import java.io.ByteArrayInputStream
import java.io.FileInputStream

object FirebaseAdmin {
    fun initialize() {
        val localServiceAccountKey = System.getProperty("FIREBASE_SERVICE_ACCOUNT_KEY_FILE")
        val serviceAccountKey = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY_FILE")

        val options = if (localServiceAccountKey != null) {
            // Use local file
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(FileInputStream(localServiceAccountKey)))
                .build()
        } else {
            // Use environment variable
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(serviceAccountKey.toByteArray())))
                .build()
        }

        FirebaseApp.initializeApp(options)
    }
}