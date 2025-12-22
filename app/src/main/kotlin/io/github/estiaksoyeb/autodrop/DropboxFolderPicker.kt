package io.github.estiaksoyeb.autodrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropboxFolderPicker(
    accessToken: String,
    onFolderSelected: (DropboxItem) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember { DropboxClient(accessToken) }
    
    // State
    val currentPath = remember { mutableStateOf("") }
    val items = remember { mutableStateOf<List<DropboxItem>>(emptyList()) }
    val isLoading = remember { mutableStateOf(false) }
    
    // Create Folder State
    val showCreateDialog = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }

    // Helper to reload
    fun loadItems() {
        scope.launch {
            isLoading.value = true
            items.value = client.listFolder(currentPath.value)
            isLoading.value = false
        }
    }

    // Load items when path changes
    LaunchedEffect(currentPath.value) {
        loadItems()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = if(currentPath.value.isEmpty()) "Dropbox" else currentPath.value.substringAfterLast("/")) },
                navigationIcon = {
                    if (currentPath.value.isNotEmpty()) {
                        IconButton(onClick = {
                            // Go up one level
                            val parent = currentPath.value.substringBeforeLast("/")
                            currentPath.value = if (parent == currentPath.value) "" else parent
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                         IconButton(onClick = onCancel) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Cancel")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog.value = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create Folder")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            
            // Current Path Header & Select Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Current: ${if(currentPath.value.isEmpty()) "Root" else currentPath.value}", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    onFolderSelected(DropboxItem(
                        name = if(currentPath.value.isEmpty()) "Root" else currentPath.value.substringAfterLast("/"),
                        pathDisplay = currentPath.value,
                        pathLower = currentPath.value.lowercase(),
                        isFolder = true
                    ))
                }) {
                    Text("Select This")
                }
            }

            Divider()

            if (isLoading.value) {
                Text("Loading...", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(items.value) { item ->
                        FolderItemRow(item = item, onClick = {
                            if (item.isFolder) {
                                currentPath.value = item.pathDisplay
                            }
                        })
                    }
                    if (items.value.isEmpty()) {
                         item {
                             Text("No items found.", modifier = Modifier.padding(16.dp))
                         }
                    }
                }
            }
        }
    }
    
    if (showCreateDialog.value) {
        AlertDialog(
            onDismissRequest = { showCreateDialog.value = false },
            title = { Text("Create New Folder") },
            text = {
                TextField(
                    value = newFolderName.value,
                    onValueChange = { newFolderName.value = it },
                    label = { Text("Folder Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.value.isNotEmpty()) {
                        scope.launch {
                            val basePath = if (currentPath.value == "") "" else currentPath.value
                            val newPath = "$basePath/${newFolderName.value}"
                            val success = client.createFolder(newPath)
                            if (success) {
                                loadItems() // Refresh list
                            }
                            showCreateDialog.value = false
                            newFolderName.value = ""
                        }
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FolderItemRow(item: DropboxItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isFolder, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple Icon placeholder
        val iconTint = if (item.isFolder) Color(0xFFFFA000) else Color.Gray
        // We don't have specific icons loaded, so we use a generic Box for now or text
        // Ideally we would use Icon(Icons.Default.Folder) but let's stick to text/color indicator if icons aren't available
        // Or better, let's just use a simple colored box to represent icon
        
        // Simulating an icon
        Icon(
            imageVector = if (item.isFolder) Icons.Default.KeyboardArrowRight else Icons.Default.Check, // Placeholder icons
            contentDescription = null,
            tint = iconTint,
             modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.isFolder) Color.Black else Color.Gray
        )
    }
}
