package io.github.estiaksoyeb.autodrop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DropboxClient(private val accessToken: String) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun listFolder(path: String = ""): List<DropboxItem> {
        return withContext(Dispatchers.IO) {
            val items = mutableListOf<DropboxItem>()
            try {
                // Dropbox API requires root path to be empty string for list_folder, 
                // but if we are browsing a subfolder, it needs the full path.
                // The API throws error if path is "/" so we convert "/" to ""
                val apiPath = if (path == "/") "" else path

                val jsonBody = JSONObject()
                jsonBody.put("path", apiPath)
                
                val request = Request.Builder()
                    .url("https://api.dropboxapi.com/2/files/list_folder")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@withContext emptyList()
                    val json = JSONObject(responseBody)
                    val entries = json.getJSONArray("entries")

                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        val tag = entry.getString(".tag")
                        val name = entry.getString("name")
                        val pathDisplay = entry.optString("path_display", name)
                        val pathLower = entry.optString("path_lower", name.lowercase())
                        val contentHash = entry.optString("content_hash", null)
                        val size = entry.optLong("size", 0)

                        items.add(DropboxItem(
                            name = name,
                            pathDisplay = pathDisplay,
                            pathLower = pathLower,
                            isFolder = tag == "folder",
                            contentHash = contentHash,
                            size = size
                        ))
                    }
                } else {
                    println("Dropbox API Error: ${response.code} - ${response.body?.string()}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            items.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        }
    }

    suspend fun uploadFile(path: String, inputStream: java.io.InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Dropbox upload API argument
                val jsonArg = JSONObject()
                jsonArg.put("path", path)
                jsonArg.put("mode", "overwrite") // "add", "overwrite", "update"
                jsonArg.put("autorename", false)
                jsonArg.put("mute", false)
                jsonArg.put("strict_conflict", false)

                val requestBody = inputStream.readBytes().toRequestBody("application/octet-stream".toMediaType())

                val request = Request.Builder()
                    .url("https://content.dropboxapi.com/2/files/upload")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Dropbox-API-Arg", jsonArg.toString())
                    .addHeader("Content-Type", "application/octet-stream")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                     println("Dropbox Upload Error: ${response.code} - ${response.body?.string()}")
                }
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun createFolder(path: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject()
                jsonBody.put("path", path)
                jsonBody.put("autorename", false)

                val request = Request.Builder()
                    .url("https://api.dropboxapi.com/2/files/create_folder_v2")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
