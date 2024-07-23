package example.com.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import kotlinx.coroutines.flow.*

sealed class VerificationState {
    data class VerificationStateSuccess(
        val firebaseToken: FirebaseToken? = null,
    ): VerificationState()
    data class VerificationStateFailure(
        val error: VerificationErrorState
    ): VerificationState()
}

sealed class VerificationErrorState {
    data object FailedToParseToken: VerificationErrorState()
    data object MissingToken: VerificationErrorState()
}

interface VerificationTokenService {
    fun isVerificationTokenValid(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState>
    fun verifyAndCheckForTokenRevoked(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState>
}

/*
 * Set of functions to verify the token found in the request.
 */
class VerificationTokenServiceImpl(
    private val auth: FirebaseAuth,
): VerificationTokenService {
    /*
     * Verifies that the token is valid and sends a VerificationState back to the caller
     *
     * If the verification step failed, this function will return VerificationStateFailure with a
     * VerificationErrorState. VerificationErrorState could be issued due to a missing token or failure to
     * parse the token.
    */
    override fun isVerificationTokenValid(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState> = flow {
                try {
                    val firebaseToken = auth.verifyIdToken(token)
                    VerificationState.VerificationStateSuccess(firebaseToken)
                }catch (e: IllegalArgumentException) {
                    VerificationState.VerificationStateFailure(
                        error = VerificationErrorState.MissingToken
                    )
                }catch (e: FirebaseAuthException) {
                    VerificationState.VerificationStateFailure(
                        error = VerificationErrorState.FailedToParseToken
                    )
                }
    }

    /*
     * Similar to isVerificationTokenValid except that it performs a additional check for if the
     * token is revoked
     */
    override fun verifyAndCheckForTokenRevoked(token: String, getFirebaseToken: Boolean): Flow<VerificationState> =
        isVerificationTokenValid(token, getFirebaseToken)
            .filterIsInstance(VerificationState.VerificationStateSuccess::class)
            .map {
                val firebaseToken = auth.verifyIdToken(token, true)
                return@map if (getFirebaseToken) VerificationState
                    .VerificationStateSuccess(firebaseToken = firebaseToken)
                        else VerificationState.VerificationStateSuccess()
            }
}