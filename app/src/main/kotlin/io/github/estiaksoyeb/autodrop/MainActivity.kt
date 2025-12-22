package io.github.estiaksoyeb.autodrop

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import androidx.compose.runtime.collectAsState
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    
    private lateinit var authManager: AuthManager
    private lateinit var syncManager: SyncManager
    
    // Simple state holder for the Activity
    private var isLoggedInState = mutableStateOf(false)
    private var userNameState = mutableStateOf("")
    private var showFolderPicker = mutableStateOf(false)
    private var selectedDropboxPath = mutableStateOf("None")
    private var selectedLocalUri = mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authManager = AuthManager(this)
        syncManager = SyncManager(this, authManager)
        
        // Load prefs
        val prefs = getSharedPreferences("autodrop_prefs", Context.MODE_PRIVATE)
        val savedLocal = prefs.getString("local_uri", null)
        val savedRemote = prefs.getString("dropbox_path", null)
        
        if (savedLocal != null) selectedLocalUri.value = android.net.Uri.parse(savedLocal)
        if (savedRemote != null) selectedDropboxPath.value = savedRemote
        
        isLoggedInState.value = authManager.hasToken()
        
        if (isLoggedInState.value) {
            fetchUserInfo()
        }

        // --- THE "CHROME" SETUP ---
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.setBackgroundColor(android.graphics.Color.WHITE)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Check if we were launched from the redirect
        checkIntent(intent)

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val syncStatus = syncManager.syncStatus.collectAsState()
            
            // Local Folder Picker Launcher
            val localFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    // Persist permission
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    
                    selectedLocalUri.value = uri
                    
                    // Save to Prefs
                    prefs.edit().putString("local_uri", uri.toString()).apply()
                    scheduleBackgroundSync()
                }
            }

            if (showFolderPicker.value && isLoggedInState.value) {
                DropboxFolderPicker(
                    accessToken = authManager.getAccessToken() ?: "",
                    onFolderSelected = { folder ->
                        selectedDropboxPath.value = folder.pathDisplay
                        showFolderPicker.value = false
                        
                        // Save to Prefs
                        prefs.edit().putString("dropbox_path", folder.pathDisplay).apply()
                        scheduleBackgroundSync()
                    },
                    onCancel = { showFolderPicker.value = false }
                )
            } else {
                val currentLocalUri = selectedLocalUri.value
                val friendlyLocalPath = if (currentLocalUri != null) getFriendlyPath(currentLocalUri) else "None"

                AppEntryPoint(
                    isLoggedIn = isLoggedInState.value,
                    userName = userNameState.value,
                    selectedPath = selectedDropboxPath.value,
                    selectedLocalPath = friendlyLocalPath,
                    syncStatus = syncStatus.value,
                    onConnect = { authManager.startAuthFlow(this) },
                    onLogout = { 
                        authManager.logout() 
                        isLoggedInState.value = false
                        userNameState.value = ""
                        selectedDropboxPath.value = "None"
                        selectedLocalUri.value = null
                        prefs.edit().clear().apply()
                        WorkManager.getInstance(this).cancelUniqueWork("AutoDropSync")
                    },
                    onPickFolder = { showFolderPicker.value = true },
                    onPickLocalFolder = { localFolderLauncher.launch(null) },
                    onSyncNow = {
                        val uri = selectedLocalUri.value
                        val path = selectedDropboxPath.value
                        if (uri != null && path != "None") {
                            lifecycleScope.launch {
                                syncManager.syncNow(uri, path)
                            }
                        }
                    }
                )
            }
        }
    }
    
    private fun scheduleBackgroundSync() {
        if (selectedLocalUri.value != null && selectedDropboxPath.value != "None") {
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AutoDropSync",
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                syncRequest
            )
        }
    }
    
    // ... existing onNewIntent, checkIntent, fetchUserInfo ...
    
    private fun getFriendlyPath(uri: android.net.Uri): String {
        val path = uri.path ?: return uri.toString()
        if (path.contains("tree/primary:")) {
            return path.replace("/tree/primary:", "/storage/emulated/0/")
        }
        return path
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // If the activity is already running (singleTask), this is called
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent == null) return
        
        lifecycleScope.launch {
            if (authManager.handleRedirect(intent)) {
                isLoggedInState.value = true
                fetchUserInfo()
            }
        }
    }

    private fun fetchUserInfo() {
        lifecycleScope.launch {
            val name = authManager.getCurrentAccount()
            if (name != null) {
                userNameState.value = name
            }
        }
    }
}

@Composable
fun AppEntryPoint(
    isLoggedIn: Boolean,
    userName: String,
    selectedPath: String,
    selectedLocalPath: String,
    syncStatus: String = "Idle",
    onConnect: () -> Unit = {},
    onLogout: () -> Unit = {},
    onPickFolder: () -> Unit = {},
    onPickLocalFolder: () -> Unit = {},
    onSyncNow: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isLoggedIn) {
                    if (userName.isNotEmpty()) "Hello, $userName!" else "Connected!"
                } else {
                    "AutoDrop"
                },
                color = Color.Black,
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (isLoggedIn) {
                 Text(text = "Dropbox Folder: $selectedPath")
                 Spacer(modifier = Modifier.height(8.dp))
                 Button(onClick = onPickFolder) {
                    Text("Select Dropbox Folder")
                 }
                 
                 Spacer(modifier = Modifier.height(16.dp))
                 
                 Text(text = "Local Folder: $selectedLocalPath")
                 Spacer(modifier = Modifier.height(8.dp))
                 Button(onClick = onPickLocalFolder) {
                    Text("Select Local Folder")
                 }

                 Spacer(modifier = Modifier.height(24.dp))
                 
                 Text(text = "Status: $syncStatus", color = Color.DarkGray)
                 Spacer(modifier = Modifier.height(8.dp))
                 Button(onClick = onSyncNow, enabled = selectedPath != "None" && selectedLocalPath != "None") {
                    Text("Sync Now")
                 }
                 
                 Spacer(modifier = Modifier.height(24.dp))
                 Button(onClick = onLogout) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Connect Dropbox")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AppEntryPoint(isLoggedIn = false, userName = "", selectedPath = "None", selectedLocalPath = "None")
}
