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

    suspend fun syncAllNow() {
        _syncStatus.value = "Starting Sync..."
        
        withContext(Dispatchers.IO) {
            val repo = SyncRepository(context)
            val pairs = repo.getSyncPairs()
            val token = authManager.getAccessToken()
            
            if (token == null) {
                _syncStatus.value = "Error: Not Logged In"
                return@withContext
            }
            
            if (pairs.isEmpty()) {
                _syncStatus.value = "No folders configured."
                repo.addLog(SyncHistoryLog(pairId = "GLOBAL", summary = "Sync Skipped", details = "No folder pairs configured"))
                return@withContext
            }

            val client = DropboxClient(token)
            var totalUploaded = 0

            for (pair in pairs) {
                if (!pair.isEnabled) continue
                
                _syncStatus.value = "Syncing ${pair.dropboxPath}..."
                var pairUploaded = 0
                
                try {
                    val localFolder = DocumentFile.fromTreeUri(context, Uri.parse(pair.localUri))
                    if (localFolder == null || !localFolder.exists()) {
                         repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Failed", details = "Local folder missing: ${pair.localUri}"))
                         continue
                    }

                    val files = localFolder.listFiles()
                    
                    for (file in files) {
                        if (file.isFile) {
                            val fileName = file.name ?: continue
                            val remotePath = if (pair.dropboxPath == "/" || pair.dropboxPath.isEmpty()) "/$fileName" else "${pair.dropboxPath}/$fileName"
                            
                            try {
                                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                                    if (client.uploadFile(remotePath, inputStream)) {
                                        totalUploaded++
                                        pairUploaded++
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    if (pairUploaded > 0) {
                        repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Success", details = "Uploaded $pairUploaded files to ${pair.dropboxPath}"))
                    } else {
                        // Optional: Log 'No changes' or skip
                         repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Completed", details = "No new files to upload for ${pair.dropboxPath}"))
                    }
                } catch (e: Exception) {
                    repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Error", details = e.message ?: "Unknown error"))
                }
            }
            
            _syncStatus.value = "Complete. Uploaded $totalUploaded files total."
            repo.addLog(SyncHistoryLog(pairId = "GLOBAL", summary = "Manual Sync Complete", details = "Total uploaded: $totalUploaded"))
        }
    }
}
