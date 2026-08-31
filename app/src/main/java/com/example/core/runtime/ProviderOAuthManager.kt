package com.example.core.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Official delegated provider sign-in. Only providers with a documented
 * OAuth/PKCE contract belong here; API-key-only providers stay in Key entry. */
class ProviderOAuthManager(
  private val vault: KeyStoreVault,
  private val http: OkHttpClient = OkHttpClient()
) {
  companion object {
    private const val VERIFIER_KEY = "OPENROUTER_OAUTH_PKCE_VERIFIER"
    private const val CALLBACK = "purpclaw://oauth/openrouter"
  }

  fun beginOpenRouter(context: Context) {
    val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
    val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val challenge = Base64.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
      Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
    check(vault.storeSecret(VERIFIER_KEY, verifier)) { "Could not persist OAuth PKCE verifier" }
    val uri = Uri.parse("https://openrouter.ai/auth").buildUpon()
      .appendQueryParameter("callback_url", CALLBACK)
      .appendQueryParameter("code_challenge", challenge)
      .appendQueryParameter("code_challenge_method", "S256")
      .build()
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  suspend fun completeOpenRouter(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      require(uri.scheme == "purpclaw" && uri.host == "oauth" && uri.path == "/openrouter")
      val code = requireNotNull(uri.getQueryParameter("code")) { "OAuth callback omitted code" }
      val verifier = requireNotNull(vault.retrieveSecret(VERIFIER_KEY)) { "OAuth verifier expired" }
      val body = JSONObject()
        .put("code", code)
        .put("code_verifier", verifier)
        .put("code_challenge_method", "S256")
        .toString().toRequestBody("application/json".toMediaType())
      val response = http.newCall(
        Request.Builder().url("https://openrouter.ai/api/v1/auth/keys").post(body).build()
      ).execute()
      val payload = response.body?.string().orEmpty()
      if (!response.isSuccessful) error("OpenRouter OAuth exchange failed (HTTP ${response.code})")
      val key = JSONObject(payload).optString("key")
      require(key.isNotBlank()) { "OpenRouter OAuth returned no key" }
      check(vault.storeSecret("OPENROUTER_API_KEY", key)) { "Could not secure OAuth key" }
      vault.deleteSecret(VERIFIER_KEY)
    }
  }
}
