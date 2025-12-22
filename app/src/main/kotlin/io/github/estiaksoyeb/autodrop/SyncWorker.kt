package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context, 
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val authManager = AuthManager(applicationContext)
            val prefs = applicationContext.getSharedPreferences("autodrop_prefs", Context.MODE_PRIVATE)
            
            val token = authManager.getAccessToken()
            val localUriString = prefs.getString("local_uri", null)
            val dropboxPath = prefs.getString("dropbox_path", null)

            if (token == null || localUriString == null || dropboxPath == null) {
                return@withContext Result.failure()
            }

            val client = DropboxClient(token)
            val localUri = Uri.parse(localUriString)
            val localFolder = DocumentFile.fromTreeUri(applicationContext, localUri)
            
            if (localFolder == null || !localFolder.exists()) {
                 return@withContext Result.failure()
            }

            val files = localFolder.listFiles()
            var successCount = 0
            
            for (file in files) {
                if (file.isFile) {
                    val fileName = file.name ?: continue
                    val remotePath = if (dropboxPath == "/" || dropboxPath.isEmpty()) "/$fileName" else "$dropboxPath/$fileName"
                    
                    try {
                        applicationContext.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                            if (client.uploadFile(remotePath, inputStream)) {
                                successCount++
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Notification or Log could go here
            Result.success()
        }
    }
}
