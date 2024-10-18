package example.com.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlin.time.DurationUnit
import kotlin.time.toDuration

sealed class VerificationState {
    data class VerificationStateSuccess(
        val firebaseToken: FirebaseToken? = null,
    ) : VerificationState()

    data class VerificationStateFailure(
        val error: VerificationErrorState? = null
    ) : VerificationState()

    data class AccountDeleted(val uid: String) : VerificationState()
}

sealed class VerificationErrorState {
    data object FailedToParseToken : VerificationErrorState()
    data object MissingToken : VerificationErrorState()
    data object TokenRevoked : VerificationErrorState()
    data object AccountDeletionError : VerificationErrorState()
}

interface VerificationTokenService {
    fun isVerificationTokenValid(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState>

    fun verifyAndCheckForTokenRevoked(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState>

    fun deleteAccount(token: String): Flow<VerificationState>
}

/*
 * Set of functions to verify the token found in the request.
 */
class VerificationTokenServiceImpl(
    private val auth: FirebaseAuth,
) : VerificationTokenService {
    /*
     * Verifies that the token is valid and sends a VerificationStateSuccess back to the caller
     * with a firebaseToken if getFirebaseToken is true
     *
     * If the verification step failed, this function will return VerificationStateFailure with a
     * VerificationErrorState. VerificationErrorState could be issued due to a missing token or failure to
     * parse the token.
    */
    override fun isVerificationTokenValid(token: String, getFirebaseToken: Boolean):
            Flow<VerificationState> = flow {
        try {
            val firebaseToken = auth.verifyIdToken(token)
            if (getFirebaseToken) {
                emit(VerificationState.VerificationStateSuccess(firebaseToken))
            } else {
                emit(VerificationState.VerificationStateSuccess())
            }
        } catch (e: IllegalArgumentException) {
            emit(
                VerificationState.VerificationStateFailure(
                    error = VerificationErrorState.MissingToken
                )
            )
        } catch (e: FirebaseAuthException) {
            emit(
                VerificationState.VerificationStateFailure(
                    error = VerificationErrorState.FailedToParseToken
                )
            )
        }
    }

    /*
     * Similar to isVerificationTokenValid except that it performs an additional check for if the
     * token is revoked
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun verifyAndCheckForTokenRevoked(token: String, getFirebaseToken: Boolean): Flow<VerificationState> =
        isVerificationTokenValid(token, getFirebaseToken)
            .mapLatest {
                try {
                    val firebaseToken = auth.verifyIdToken(token, true)
                    if (getFirebaseToken) VerificationState
                        .VerificationStateSuccess(firebaseToken = firebaseToken)
                    else VerificationState.VerificationStateSuccess()
                } catch (e: Exception) {
                    VerificationState.VerificationStateFailure(error = VerificationErrorState.TokenRevoked)
                }
            }

    override fun deleteAccount(token: String) = isVerificationTokenValid(token, true)
        .filterIsInstance<VerificationState.VerificationStateSuccess>()
        .mapNotNull { state ->
            auth.deleteUser(state.firebaseToken?.uid)
            VerificationState.AccountDeleted(state.firebaseToken?.uid ?: "")
        }
}