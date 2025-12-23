package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

// Snapshot Model
data class FileSnapshot(
    val path: String, // Relative path from root
    val hash: String?
)

object SyncUtils {

    suspend fun performSync(
        context: Context,
        pair: SyncPair,
        client: DropboxClient,
        onLog: (SyncHistoryLog) -> Unit
    ): Int {
        onLog(SyncHistoryLog(message = "=== Sync started (${pair.dropboxPath}) ===", type = LogType.START))
        
        val localFolder = DocumentFile.fromTreeUri(context, Uri.parse(pair.localUri))
        if (localFolder == null) {
            onLog(SyncHistoryLog(message = "ERROR: Local folder missing: ${pair.localUri}", type = LogType.ERROR))
            return 0
        }
        
        val count = when (pair.syncMethod) {
            SyncMethod.UPLOAD_ONLY -> syncUpload(context, client, localFolder, pair.dropboxPath, pair.excludedPaths, isMirror = false, onLog)
            SyncMethod.MIRROR_UPLOAD -> syncUpload(context, client, localFolder, pair.dropboxPath, pair.excludedPaths, isMirror = true, onLog)
            SyncMethod.DOWNLOAD_ONLY -> syncDownload(context, client, localFolder, pair.dropboxPath, pair.excludedPaths, isMirror = false, onLog)
            SyncMethod.MIRROR_DOWNLOAD -> syncDownload(context, client, localFolder, pair.dropboxPath, pair.excludedPaths, isMirror = true, onLog)
            SyncMethod.TWO_WAY -> syncTwoWay(context, pair.id, client, localFolder, pair.dropboxPath, pair.excludedPaths, onLog)
        }
        
        onLog(SyncHistoryLog(message = "=== Sync completed ===", type = LogType.END))
        return count
    }

    // 1. Upload (Local -> Remote)
    private suspend fun syncUpload(
        context: Context,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        excludedPaths: List<String>,
        isMirror: Boolean,
        onLog: (SyncHistoryLog) -> Unit
    ): Int {
        var count = 0
        val localFiles = localFolder.listFiles()
        val remoteItems = client.listFolder(remoteBasePath)
        val remoteMap = remoteItems.associateBy { it.name }
        
        // A. Upload / Update
        for (file in localFiles) {
            val name = file.name ?: continue
            if (isExcluded(name, excludedPaths)) continue
            
            val remoteItem = remoteMap[name]
            val remotePath = joinPath(remoteBasePath, name)
            
            if (file.isDirectory) {
                count += syncUpload(context, client, file, remotePath, excludedPaths, isMirror, onLog)
                onLog(SyncHistoryLog(message = "Folder sync completed: $name", type = LogType.INFO))
            } else {
                val shouldUpload = if (remoteItem != null && remoteItem.contentHash != null) {
                    val localHash = calculateDropboxHash(context, file.uri)
                    localHash != remoteItem.contentHash
                } else {
                    true
                }
                
                if (shouldUpload) {
                    onLog(SyncHistoryLog(message = "[UPLOAD] Local -> Remote: $name", type = LogType.INFO))
                    if (uploadFile(context, client, file.uri, remotePath)) count++
                    else onLog(SyncHistoryLog(message = "ERROR: Failed to upload $name", type = LogType.ERROR))
                }
            }
        }
        
        // B. Delete Remote (Only if Mirror)
        if (isMirror) {
            val localNames = localFiles.mapNotNull { it.name }.toSet()
            for (remoteItem in remoteItems) {
                if (isExcluded(remoteItem.name, excludedPaths)) continue
                
                if (!localNames.contains(remoteItem.name)) {
                    onLog(SyncHistoryLog(message = "[DELETE] Remote: ${remoteItem.name}", type = LogType.INFO))
                    if (client.deleteFile(remoteItem.pathDisplay)) count++
                    else onLog(SyncHistoryLog(message = "ERROR: Failed to delete remote ${remoteItem.name}", type = LogType.ERROR))
                }
            }
        }
        return count
    }

    // 2. Download (Remote -> Local)
    private suspend fun syncDownload(
        context: Context,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        excludedPaths: List<String>,
        isMirror: Boolean,
        onLog: (SyncHistoryLog) -> Unit
    ): Int {
        var count = 0
        val remoteItems = client.listFolder(remoteBasePath)
        val localFiles = localFolder.listFiles()
        val localMap = localFiles.filter { it.name != null }.associateBy { it.name!! }
        
        // A. Download / Update
        for (remoteItem in remoteItems) {
            if (isExcluded(remoteItem.name, excludedPaths)) continue
            
            val localFile = localMap[remoteItem.name]
            
            if (remoteItem.isFolder) {
                val targetFolder = localFile ?: localFolder.createDirectory(remoteItem.name)
                if (targetFolder != null && targetFolder.isDirectory) {
                    count += syncDownload(context, client, targetFolder, remoteItem.pathDisplay, excludedPaths, isMirror, onLog)
                    onLog(SyncHistoryLog(message = "Folder sync completed: ${remoteItem.name}", type = LogType.INFO))
                }
            } else {
                val shouldDownload = if (localFile != null) {
                    val localHash = calculateDropboxHash(context, localFile.uri)
                    localHash != remoteItem.contentHash
                } else {
                    true
                }
                
                if (shouldDownload) {
                    onLog(SyncHistoryLog(message = "[DOWNLOAD] Remote -> Local: ${remoteItem.name}", type = LogType.INFO))
                    val destFile = localFile ?: localFolder.createFile("application/octet-stream", remoteItem.name)
                    if (destFile != null) {
                        if (downloadFile(context, client, remoteItem.pathDisplay, destFile)) count++
                        else onLog(SyncHistoryLog(message = "ERROR: Failed to download ${remoteItem.name}", type = LogType.ERROR))
                    } else {
                         onLog(SyncHistoryLog(message = "ERROR: Failed to create local file ${remoteItem.name}", type = LogType.ERROR))
                    }
                }
            }
        }
        
        // B. Delete Local (Only if Mirror)
        if (isMirror) {
            val remoteNames = remoteItems.map { it.name }.toSet()
            for (file in localFiles) {
                val name = file.name ?: continue
                if (isExcluded(name, excludedPaths)) continue
                
                if (!remoteNames.contains(name)) {
                    onLog(SyncHistoryLog(message = "[DELETE] Local: $name", type = LogType.INFO))
                    if (file.delete()) count++
                    else onLog(SyncHistoryLog(message = "ERROR: Failed to delete local $name", type = LogType.ERROR))
                }
            }
        }
        return count
    }

    // 3. Two-Way Sync
    private suspend fun syncTwoWay(
        context: Context,
        pairId: String,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        excludedPaths: List<String>,
        onLog: (SyncHistoryLog) -> Unit
    ): Int {
        val snapshotFile = File(context.filesDir, "snapshot_$pairId.json")
        val gson = Gson()
        val type = object : TypeToken<Map<String, FileSnapshot>>() {}.type
        val oldSnapshot: Map<String, FileSnapshot> = if (snapshotFile.exists()) {
             try { gson.fromJson(snapshotFile.readText(), type) } catch(e: Exception) { emptyMap() }
        } else {
            emptyMap()
        }
        
        val newSnapshot = mutableMapOf<String, FileSnapshot>()
        
        val count = syncTwoWayRecursive(
            context, client, localFolder, remoteBasePath, "", 
            excludedPaths, oldSnapshot, newSnapshot, onLog
        )
        
        snapshotFile.writeText(gson.toJson(newSnapshot))
        return count
    }

    private suspend fun syncTwoWayRecursive(
        context: Context,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        relativePath: String,
        excludedPaths: List<String>,
        oldSnapshot: Map<String, FileSnapshot>,
        newSnapshot: MutableMap<String, FileSnapshot>,
        onLog: (SyncHistoryLog) -> Unit
    ): Int {
        var count = 0
        
        val localFiles = localFolder.listFiles()
        val localMap = localFiles.filter { it.name != null }.associateBy { it.name!! }
        
        val remoteItems = client.listFolder(remoteBasePath)
        val remoteMap = remoteItems.associateBy { it.name }
        
        val allNames = (localMap.keys + remoteMap.keys).toSet()
        
        for (name in allNames) {
            val itemPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
            if (isExcluded(itemPath, excludedPaths)) continue
            
            val localFile = localMap[name]
            val remoteItem = remoteMap[name]
            val snapshotItem = oldSnapshot[itemPath]
            
            val isFolder = (localFile?.isDirectory == true) || (remoteItem?.isFolder == true)
            
            if (isFolder) {
                if (remoteItem != null && remoteItem.isFolder && localFile == null) {
                     val created = localFolder.createDirectory(name)
                     if (created != null) {
                         count += syncTwoWayRecursive(context, client, created, remoteItem.pathDisplay, itemPath, excludedPaths, oldSnapshot, newSnapshot, onLog)
                         onLog(SyncHistoryLog(message = "Folder sync completed: $name", type = LogType.INFO))
                     }
                } else if (localFile != null && localFile.isDirectory && remoteItem == null) {
                     count += syncTwoWayRecursive(context, client, localFile, joinPath(remoteBasePath, name), itemPath, excludedPaths, oldSnapshot, newSnapshot, onLog)
                     onLog(SyncHistoryLog(message = "Folder sync completed: $name", type = LogType.INFO))
                } else if (localFile != null && remoteItem != null) {
                     count += syncTwoWayRecursive(context, client, localFile, remoteItem.pathDisplay, itemPath, excludedPaths, oldSnapshot, newSnapshot, onLog)
                     onLog(SyncHistoryLog(message = "Folder sync completed: $name", type = LogType.INFO))
                }
                continue
            }
            
            val localHash = if (localFile != null) calculateDropboxHash(context, localFile.uri) else null
            val remoteHash = remoteItem?.contentHash
            
            val localChanged = localFile != null && (snapshotItem == null || localHash != snapshotItem.hash)
            val remoteChanged = remoteItem != null && (snapshotItem == null || remoteHash != snapshotItem.hash)
            val wasInSnapshot = snapshotItem != null
            
            if (localFile != null && remoteItem != null) {
                if (localHash == remoteHash) {
                    newSnapshot[itemPath] = FileSnapshot(itemPath, localHash)
                } else {
                    if (localChanged && !remoteChanged) {
                        onLog(SyncHistoryLog(message = "[UPLOAD] Local -> Remote: $name", type = LogType.INFO))
                        if (uploadFile(context, client, localFile.uri, remoteItem.pathDisplay)) {
                             newSnapshot[itemPath] = FileSnapshot(itemPath, localHash)
                             count++
                        } else onLog(SyncHistoryLog(message = "ERROR: Failed to upload $name", type = LogType.ERROR))
                    } else if (remoteChanged && !localChanged) {
                         onLog(SyncHistoryLog(message = "[DOWNLOAD] Remote -> Local: $name", type = LogType.INFO))
                         if (downloadFile(context, client, remoteItem.pathDisplay, localFile)) {
                             newSnapshot[itemPath] = FileSnapshot(itemPath, remoteHash)
                             count++
                         } else onLog(SyncHistoryLog(message = "ERROR: Failed to download $name", type = LogType.ERROR))
                    } else {
                        // Conflict
                        onLog(SyncHistoryLog(
                            message = "CONFLICT detected: $name", 
                            type = LogType.CONFLICT, 
                            details = "Local Hash: $localHash\nRemote Hash: $remoteHash"
                        ))
                        val conflictName = appendConflict(name)
                        localFile.renameTo(conflictName)
                        
                        val newFile = localFolder.createFile("application/octet-stream", name)
                        if (newFile != null) {
                             if (downloadFile(context, client, remoteItem.pathDisplay, newFile)) {
                                 newSnapshot[itemPath] = FileSnapshot(itemPath, remoteHash)
                                 count++
                             }
                        }
                    }
                }
            } else if (localFile != null && remoteItem == null) {
                if (wasInSnapshot) {
                     onLog(SyncHistoryLog(message = "[DELETE] Local: $name", type = LogType.INFO))
                     if (localFile.delete()) count++ 
                     else onLog(SyncHistoryLog(message = "ERROR: Failed to delete local $name", type = LogType.ERROR))
                } else {
                    onLog(SyncHistoryLog(message = "[UPLOAD] New Local -> Remote: $name", type = LogType.INFO))
                     if (uploadFile(context, client, localFile.uri, joinPath(remoteBasePath, name))) {
                         newSnapshot[itemPath] = FileSnapshot(itemPath, localHash)
                         count++
                     } else onLog(SyncHistoryLog(message = "ERROR: Failed to upload $name", type = LogType.ERROR))
                }
            } else if (localFile == null && remoteItem != null) {
                if (wasInSnapshot) {
                     onLog(SyncHistoryLog(message = "[DELETE] Remote: $name", type = LogType.INFO))
                     if (client.deleteFile(remoteItem.pathDisplay)) count++
                     else onLog(SyncHistoryLog(message = "ERROR: Failed to delete remote $name", type = LogType.ERROR))
                } else {
                     onLog(SyncHistoryLog(message = "[DOWNLOAD] New Remote -> Local: $name", type = LogType.INFO))
                     val newFile = localFolder.createFile("application/octet-stream", name)
                     if (newFile != null) {
                         if (downloadFile(context, client, remoteItem.pathDisplay, newFile)) {
                             newSnapshot[itemPath] = FileSnapshot(itemPath, remoteHash)
                             count++
                         } else onLog(SyncHistoryLog(message = "ERROR: Failed to download $name", type = LogType.ERROR))
                     } else onLog(SyncHistoryLog(message = "ERROR: Failed to create file $name", type = LogType.ERROR))
                }
            }
        }
        return count
    }

    private suspend fun uploadFile(context: Context, client: DropboxClient, uri: Uri, path: String): Boolean {
         return try {
             context.contentResolver.openInputStream(uri)?.use { 
                 client.uploadFile(path, it) 
             } ?: false
         } catch(e: Exception) { false }
    }
    
    private suspend fun downloadFile(context: Context, client: DropboxClient, path: String, file: DocumentFile): Boolean {
        return try {
            context.contentResolver.openOutputStream(file.uri)?.use { 
                client.downloadFile(path, it) 
            }
            true
        } catch(e: Exception) { 
            e.printStackTrace()
            false 
        }
    }

    private fun joinPath(base: String, name: String): String {
        return if (base == "/" || base.isEmpty()) "/$name" else "$base/$name"
    }
    
    private fun isExcluded(path: String, excludedPaths: List<String>): Boolean {
         return excludedPaths.any { path == it || path.startsWith("$it/") || (it.contains("/") && path.endsWith("/$it")) || path == it }
    }
    
    private fun appendConflict(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot != -1) {
            "${name.substring(0, dot)} (conflict)${name.substring(dot)}"
        } else {
            "$name (conflict)"
        }
    }

    private fun calculateDropboxHash(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val overallDigest = MessageDigest.getInstance("SHA-256")
                val blockHashes = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024 * 1024)
                
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val blockDigest = MessageDigest.getInstance("SHA-256")
                    blockDigest.update(buffer, 0, bytesRead)
                    blockHashes.write(blockDigest.digest())
                }
                
                overallDigest.update(blockHashes.toByteArray())
                return toHex(overallDigest.digest())
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}