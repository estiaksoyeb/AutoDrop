package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class SyncManager(private val context: Context, private val authManager: AuthManager) {

    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus: StateFlow<String> = _syncStatus

    suspend fun syncNow(localUri: Uri, dropboxPath: String) {
        _syncStatus.value = "Starting Sync..."
        
        withContext(Dispatchers.IO) {
            val token = authManager.getAccessToken()
            if (token == null) {
                _syncStatus.value = "Error: Not Logged In"
                return@withContext
            }

            val client = DropboxClient(token)
            val localFolder = DocumentFile.fromTreeUri(context, localUri)
            
            if (localFolder == null || !localFolder.exists()) {
                 _syncStatus.value = "Error: Local folder not found"
                 return@withContext
            }

            val files = localFolder.listFiles()
            var uploadedCount = 0
            
            for (file in files) {
                if (file.isFile) { // Only syncing top-level files for now
                    val fileName = file.name ?: continue
                    _syncStatus.value = "Uploading: $fileName"
                    
                    val remotePath = if (dropboxPath == "/" || dropboxPath.isEmpty()) "/$fileName" else "$dropboxPath/$fileName"
                    
                    try {
                        context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                            val success = client.uploadFile(remotePath, inputStream)
                            if (success) uploadedCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            _syncStatus.value = "Sync Complete. Uploaded $uploadedCount files."
        }
    }
}
