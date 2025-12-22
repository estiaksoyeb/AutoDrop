package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class AuthManager(private val context: Context) {

    companion object {
        const val APP_KEY = "6gzu6iac2g7ud1q" // Your Dropbox App Key
        const val REDIRECT_URI = "autodrop://oauth2redirect"
        private const val PREFS_FILENAME = "secure_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_VERIFIER = "code_verifier" // We need to store this temporarily
    }

    // 1. Secure Storage Setup
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val client = OkHttpClient()

    // 2. Check if user is logged in
    fun hasToken(): Boolean {
        return sharedPreferences.contains(KEY_ACCESS_TOKEN)
    }

    fun getAccessToken(): String? {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }
    
    fun logout() {
        sharedPreferences.edit().clear().apply()
    }

    suspend fun getCurrentAccount(): String? {
        return withContext(Dispatchers.IO) {
            val token = getAccessToken() ?: return@withContext null
            try {
                val request = Request.Builder()
                    .url("https://api.dropboxapi.com/2/users/get_current_account")
                    .post(FormBody.Builder().build()) // Empty body for this POST
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    return@withContext json.getJSONObject("name").getString("display_name")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    // 3. Start Auth Flow (Generate PKCE & URL)
    fun startAuthFlow(context: Context) {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        // Save verifier for step 2 (exchange)
        sharedPreferences.edit().putString(KEY_VERIFIER, verifier).apply()

        val authUrl = "https://www.dropbox.com/oauth2/authorize" +
                "?client_id=$APP_KEY" +
                "&response_type=code" +
                "&token_access_type=offline" +
                "&redirect_uri=$REDIRECT_URI" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        context.startActivity(intent)
    }

    // 4. Handle Redirect & Exchange Code
    suspend fun handleRedirect(intent: Intent): Boolean {
        val data: Uri? = intent.data
        if (data != null && data.toString().startsWith(REDIRECT_URI)) {
            val code = data.getQueryParameter("code") ?: return false
            val verifier = sharedPreferences.getString(KEY_VERIFIER, null) ?: return false

            return exchangeCodeForToken(code, verifier)
        }
        return false
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = FormBody.Builder()
                    .add("code", code)
                    .add("grant_type", "authorization_code")
                    .add("client_id", APP_KEY)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", REDIRECT_URI)
                    .build()

                val request = Request.Builder()
                    .url("https://api.dropboxapi.com/oauth2/token")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonResponse = response.body?.string() ?: return@withContext false
                    val json = JSONObject(jsonResponse)

                    val accessToken = json.optString("access_token")
                    val refreshToken = json.optString("refresh_token")

                    if (accessToken.isNotEmpty()) {
                        sharedPreferences.edit()
                            .putString(KEY_ACCESS_TOKEN, accessToken)
                            .apply()
                        
                        if (refreshToken.isNotEmpty()) {
                             sharedPreferences.edit()
                                .putString(KEY_REFRESH_TOKEN, refreshToken)
                                .apply()
                        }
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext false
        }
    }

    // --- PKCE Helpers ---

    private fun generateCodeVerifier(): String {
        val sr = SecureRandom()
        val code = ByteArray(32)
        sr.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, bytes.size)
        val digest = md.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
