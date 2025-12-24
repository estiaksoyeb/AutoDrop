package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupScreen(
    draftPair: SyncPair,
    onUpdateDraft: (SyncPair) -> Unit,
    onPickLocal: () -> Unit,
    onPickRemote: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var isMethodExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Helper to calculate relative path from rootUri to pickedUri
    fun getRelativePath(rootUriStr: String, itemUri: Uri): String? {
        try {
            val rootUri = Uri.parse(rootUriStr)
            val rootId = DocumentsContract.getTreeDocumentId(rootUri)
            
            val itemId = if (DocumentsContract.isDocumentUri(context, itemUri)) {
                DocumentsContract.getDocumentId(itemUri)
            } else {
                DocumentsContract.getTreeDocumentId(itemUri)
            }

            val decodedRoot = Uri.decode(rootId)
            val decodedItem = Uri.decode(itemId)

            if (decodedItem.startsWith(decodedRoot)) {
                var relative = decodedItem.substring(decodedRoot.length)
                
                // Remove leading separators like ":" or "/"
                while (relative.startsWith(":") || relative.startsWith("/")) {
                    relative = relative.substring(1)
                }
                return relative
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Launchers for Exclusions
    val excludeFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (draftPair.localUri.isEmpty()) return@let
            val relativePath = getRelativePath(draftPair.localUri, it)
            if (!relativePath.isNullOrBlank()) {
                 val newList = draftPair.excludedPaths.toMutableList()
                 if (!newList.contains(relativePath)) {
                     newList.add(relativePath)
                     onUpdateDraft(draftPair.copy(excludedPaths = newList))
                 }
            } else {
                // Fallback to name if not relative
                val file = DocumentFile.fromSingleUri(context, it)
                file?.name?.let { name ->
                    val newList = draftPair.excludedPaths.toMutableList()
                    if (!newList.contains(name)) {
                        newList.add(name)
                        onUpdateDraft(draftPair.copy(excludedPaths = newList))
                    }
                }
            }
        }
    }

    val excludeFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            if (draftPair.localUri.isEmpty()) return@let
            val relativePath = getRelativePath(draftPair.localUri, it)
            if (!relativePath.isNullOrBlank()) {
                 val newList = draftPair.excludedPaths.toMutableList()
                 if (!newList.contains(relativePath)) {
                     newList.add(relativePath)
                     onUpdateDraft(draftPair.copy(excludedPaths = newList))
                 }
            } else {
                // Fallback to name
                val file = DocumentFile.fromTreeUri(context, it)
                file?.name?.let { name ->
                    val newList = draftPair.excludedPaths.toMutableList()
                    if (!newList.contains(name)) {
                        newList.add(name)
                        onUpdateDraft(draftPair.copy(excludedPaths = newList))
                    }
                }
            }
        }
    }

    fun getFriendlyLocal(uri: String): String {
        if (uri.isEmpty()) return "Select Local Folder"
        val path = android.net.Uri.parse(uri).path ?: return uri
        if (path.contains("tree/primary:")) {
            return path.replace("/tree/primary:", "/storage/emulated/0/")
        }
        return path
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Backup") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .background(Color.White)
        ) {
            // Local Folder
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable { onPickLocal() },
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Local Folder", style = MaterialTheme.typography.labelMedium)
                        Text(
                            getFriendlyLocal(draftPair.localUri),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Remote Folder
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable { onPickRemote() },
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Dropbox Folder", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (draftPair.dropboxPath.isEmpty()) "Root (/)" else draftPair.dropboxPath,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync Method Dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = when (draftPair.syncMethod) {
                        SyncMethod.UPLOAD_ONLY -> "Upload Only (Local -> Cloud)"
                        SyncMethod.DOWNLOAD_ONLY -> "Download Only (Cloud -> Local)"
                        SyncMethod.MIRROR_UPLOAD -> "Mirror Upload (Destructive)"
                        SyncMethod.MIRROR_DOWNLOAD -> "Mirror Download (Destructive)"
                        SyncMethod.TWO_WAY -> "Two Way Sync"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sync Method") },
                    trailingIcon = {
                        IconButton(onClick = { isMethodExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Method")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = isMethodExpanded,
                    onDismissRequest = { isMethodExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Upload Only (Local -> Cloud)") },
                        onClick = {
                            onUpdateDraft(draftPair.copy(syncMethod = SyncMethod.UPLOAD_ONLY))
                            isMethodExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download Only (Cloud -> Local)") },
                        onClick = {
                            onUpdateDraft(draftPair.copy(syncMethod = SyncMethod.DOWNLOAD_ONLY))
                            isMethodExpanded = false
                        }
                    )
                     DropdownMenuItem(
                        text = { Text("Mirror Upload (Destructive)") },
                        onClick = {
                            onUpdateDraft(draftPair.copy(syncMethod = SyncMethod.MIRROR_UPLOAD))
                            isMethodExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Mirror Download (Destructive)") },
                        onClick = {
                            onUpdateDraft(draftPair.copy(syncMethod = SyncMethod.MIRROR_DOWNLOAD))
                            isMethodExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Two Way Sync") },
                        onClick = {
                            onUpdateDraft(draftPair.copy(syncMethod = SyncMethod.TWO_WAY))
                            isMethodExpanded = false
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Exclude Section
            Text("Exclude Folders/Files", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { excludeFolderLauncher.launch(null) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Exclude Folder")
                }
                Button(onClick = { excludeFileLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Exclude File")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Excluded List
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(draftPair.excludedPaths) { path ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "/$path", 
                            modifier = Modifier.padding(start = 8.dp).weight(1f), 
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(onClick = {
                            val newList = draftPair.excludedPaths.toMutableList()
                            newList.remove(path)
                            onUpdateDraft(draftPair.copy(excludedPaths = newList))
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Include (Remove from exclude)", tint = Color.Red)
                        }
                    }
                    Divider()
                }
                if (draftPair.excludedPaths.isEmpty()) {
                    item {
                         Text("No exclusions added.", color = Color.Gray, modifier = Modifier.padding(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save Button
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = draftPair.localUri.isNotEmpty()
            ) {
                Text("Save Configuration")
            }
        }
    }
}
