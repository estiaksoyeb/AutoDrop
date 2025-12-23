package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object SyncUtils {

    suspend fun syncFolderRecursively(
        context: Context,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        excludedPaths: List<String>,
        onUpload: () -> Unit
    ): Int {
        return syncInternal(context, client, localFolder, remoteBasePath, "", excludedPaths, onUpload)
    }

    private suspend fun syncInternal(
        context: Context,
        client: DropboxClient,
        localFolder: DocumentFile,
        remoteBasePath: String,
        currentRelativePath: String,
        excludedPaths: List<String>,
        onUpload: () -> Unit
    ): Int {
        var uploadedCount = 0
        val files = localFolder.listFiles()

        // Fetch Remote Metadata for current folder to compare
        val remoteItems = client.listFolder(remoteBasePath)
        val remoteMap = remoteItems.associateBy { it.name }

        for (file in files) {
            val name = file.name ?: continue
            
            // Calculate relative path for exclusion check
            val itemRelativePath = if (currentRelativePath.isEmpty()) name else "$currentRelativePath/$name"
            
            // Exclude by relative path
            if (excludedPaths.contains(itemRelativePath)) continue
            
            if (file.isDirectory) {
                val newRemoteBase = if (remoteBasePath == "/" || remoteBasePath.isEmpty()) "/$name" else "$remoteBasePath/$name"
                
                uploadedCount += syncInternal(
                    context, 
                    client, 
                    file, 
                    newRemoteBase,
                    itemRelativePath,
                    excludedPaths, 
                    onUpload
                )
            } else {
                val remoteItem = remoteMap[name]
                val shouldUpload = if (remoteItem != null && remoteItem.contentHash != null) {
                    val localHash = calculateDropboxHash(context, file.uri)
                    if (localHash == null) true else localHash != remoteItem.contentHash
                } else {
                    true
                }

                if (shouldUpload) {
                    val remotePath = if (remoteBasePath == "/" || remoteBasePath.isEmpty()) "/$name" else "$remoteBasePath/$name"
                    
                    try {
                        context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                            if (client.uploadFile(remotePath, inputStream)) {
                                uploadedCount++
                                onUpload()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return uploadedCount
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
