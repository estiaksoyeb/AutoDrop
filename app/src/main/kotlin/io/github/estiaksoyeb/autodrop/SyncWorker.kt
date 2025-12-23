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
            val repo = SyncRepository(applicationContext)
            
            val token = authManager.getAccessToken()
            if (token == null) return@withContext Result.failure()

            val client = DropboxClient(token)
            val pairs = repo.getSyncPairs()
            
            if (pairs.isEmpty()) return@withContext Result.success()

            for (pair in pairs) {
                if (!pair.isEnabled) continue
                
                repo.updateSyncStatus(pair.id, "Syncing...")
                
                try {
                    val localUri = Uri.parse(pair.localUri)
                    val localFolder = DocumentFile.fromTreeUri(applicationContext, localUri)
                    
                    if (localFolder == null || !localFolder.exists()) {
                         repo.updateSyncStatus(pair.id, "Error: Folder missing")
                         repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Auto-Sync Failed", details = "Folder missing: ${pair.localUri}"))
                         continue
                    }

                    // Recursive Sync via Utils
                    val changeCount = SyncUtils.performSync(
                        context = applicationContext,
                        pair = pair,
                        client = client,
                        onProgress = { msg -> repo.updateSyncStatus(pair.id, msg) }
                    )

                    repo.updateSyncStatus(pair.id, "Success: $changeCount changes")
                    if (changeCount > 0) {
                        repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Auto-Sync Success", details = "Processed $changeCount changes"))
                    }
                } catch (e: Exception) {
                    repo.updateSyncStatus(pair.id, "Error: ${e.message}")
                    repo.addLog(SyncHistoryLog(pairId = pair.id, summary = "Auto-Sync Error", details = e.message ?: "Unknown"))
                }
            }
            
            Result.success()
        }
    }
}
