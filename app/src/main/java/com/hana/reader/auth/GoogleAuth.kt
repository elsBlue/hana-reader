package com.hana.reader.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import org.json.JSONObject

sealed class GoogleSignInOutcome {
    data class Success(val email: String, val profilePicUrl: String?) : GoogleSignInOutcome()
    data object Canceled : GoogleSignInOutcome()
    data class Failed(val message: String) : GoogleSignInOutcome()
}

object GoogleAuth {
    const val WEB_CLIENT_ID =
        "11378030984-giam2jtt3ckg0n16670a1v35nm9prceb.apps.googleusercontent.com"

    private const val TAG = "GoogleAuth"

    fun findActivity(context: Context): Activity? {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    suspend fun signIn(activity: Activity): GoogleSignInOutcome {
        return try {
            val credentialManager = CredentialManager.create(activity)
            val signInOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val result = credentialManager.getCredential(context = activity, request = request)
            val credential = result.credential
            val googleId = when {
                credential is CustomCredential &&
                    (
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
                        ) -> GoogleIdTokenCredential.createFrom(credential.data)
                credential is CustomCredential -> GoogleIdTokenCredential.createFrom(credential.data)
                else -> return GoogleSignInOutcome.Failed(
                    "Unexpected credential: ${credential.javaClass.simpleName}"
                )
            }
            val email = resolveAccountEmail(googleId)
            if (email.isNullOrBlank()) {
                return GoogleSignInOutcome.Failed("Google did not return an email.")
            }
            GoogleSignInOutcome.Success(email, googleId.profilePictureUri?.toString())
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInOutcome.Canceled
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google sign in failed", e)
            GoogleSignInOutcome.Failed(
                listOfNotNull(e.javaClass.simpleName, e.message?.takeIf { it.isNotBlank() })
                    .joinToString(": ")
                    .ifBlank { "Google sign-in failed." }
            )
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInOutcome.Failed("Invalid Google ID token")
        } catch (e: Exception) {
            GoogleSignInOutcome.Failed(e.message ?: "Google sign-in failed")
        }
    }

    private fun resolveAccountEmail(credential: GoogleIdTokenCredential): String? {
        val fromId = credential.id.trim()
        if (fromId.contains("@")) return fromId
        return try {
            val parts = credential.idToken.split(".")
            if (parts.size < 2) return fromId.ifBlank { null }
            var payload = parts[1]
            val pad = (4 - payload.length % 4) % 4
            if (pad > 0) payload += "=".repeat(pad)
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            val email = JSONObject(json).optString("email").trim()
            when {
                email.contains("@") -> email
                fromId.isNotBlank() -> fromId
                else -> null
            }
        } catch (_: Exception) {
            fromId.ifBlank { null }
        }
    }
}
