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
                
                try {
                    val localFolder = DocumentFile.fromTreeUri(context, Uri.parse(pair.localUri))
                    if (localFolder == null || !localFolder.exists()) {
                         repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Failed", details = "Local folder missing: ${pair.localUri}"))
                         continue
                    }

                    // Use performSync
                    val changeCount = SyncUtils.performSync(
                        context = context,
                        pair = pair,
                        client = client,
                        onProgress = { msg -> _syncStatus.value = msg }
                    )

                    if (changeCount > 0) {
                        repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Success", details = "Processed $changeCount changes"))
                        totalUploaded += changeCount
                    } else {
                         repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Completed", details = "No changes for ${pair.dropboxPath}"))
                    }
                } catch (e: Exception) {
                    repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Sync Error", details = e.message ?: "Unknown error"))
                }
            }
            
            _syncStatus.value = "Complete. Processed $totalUploaded items."
            repo.addLog(SyncHistoryLog(pairId = "GLOBAL", summary = "Manual Sync Complete", details = "Total items processed: $totalUploaded"))
        }
    }
}
