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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    
    private lateinit var authManager: AuthManager
    private lateinit var syncManager: SyncManager
    private lateinit var syncRepository: SyncRepository
    
    // State
    private var isLoggedInState = mutableStateOf(false)
    private var userNameState = mutableStateOf("")
    private var syncPairsState = mutableStateOf<List<SyncPair>>(emptyList())
    private var historyLogsState = mutableStateOf<List<SyncHistoryLog>>(emptyList())
    
    // Navigation State
    private var currentScreen = mutableStateOf("home") // "home" or "history"
    
    // Add Flow State
    private var isAddingPair = mutableStateOf(false)
    private var tempDropboxPath = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authManager = AuthManager(this)
        syncManager = SyncManager(this, authManager)
        syncRepository = SyncRepository(this)
        
        // Load initial state
        isLoggedInState.value = authManager.hasToken()
        if (isLoggedInState.value) {
            fetchUserInfo()
            loadData()
        }

        // --- THE "CHROME" SETUP ---
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.setBackgroundColor(android.graphics.Color.WHITE)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        checkIntent(intent)

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val syncStatus = syncManager.syncStatus.collectAsState()
            
            // Local Folder Picker Launcher
            val localFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    
                    val dropboxPath = tempDropboxPath.value
                    if (dropboxPath != null) {
                        val newPair = SyncPair(
                            localUri = uri.toString(),
                            dropboxPath = dropboxPath
                        )
                        syncRepository.addSyncPair(newPair)
                        loadData() // Refresh list
                        scheduleBackgroundSync()
                    }
                    
                    // Reset State
                    isAddingPair.value = false
                    tempDropboxPath.value = null
                } else {
                    // Cancelled local picker
                     isAddingPair.value = false
                     tempDropboxPath.value = null
                }
            }

            if (isAddingPair.value && isLoggedInState.value) {
                DropboxFolderPicker(
                    accessToken = authManager.getAccessToken() ?: "",
                    onFolderSelected = { folder ->
                        tempDropboxPath.value = folder.pathDisplay
                        localFolderLauncher.launch(null)
                    },
                    onCancel = { 
                        isAddingPair.value = false 
                        tempDropboxPath.value = null
                    }
                )
            } else {
                MainScreen(
                    currentScreen = currentScreen.value,
                    isLoggedIn = isLoggedInState.value,
                    userName = userNameState.value,
                    syncPairs = syncPairsState.value,
                    historyLogs = historyLogsState.value,
                    globalSyncStatus = syncStatus.value,
                    onNavigate = { currentScreen.value = it },
                    onConnect = { authManager.startAuthFlow(this) },
                    onLogout = { 
                        authManager.logout() 
                        isLoggedInState.value = false
                        userNameState.value = ""
                        syncRepository.clearAll()
                        loadData()
                        WorkManager.getInstance(this).cancelUniqueWork("AutoDropSync")
                    },
                    onAddPair = { isAddingPair.value = true },
                    onDeletePair = { id ->
                        syncRepository.removeSyncPair(id)
                        loadData()
                    },
                    onSyncAll = {
                        lifecycleScope.launch {
                            syncManager.syncAllNow()
                            loadData() // Refresh history after sync
                        }
                    },
                    onClearHistory = {
                        syncRepository.clearHistory()
                        loadData()
                    },
                    onRefreshHistory = {
                         loadData()
                    }
                )
            }
        }
    }
    
    private fun loadData() {
        syncPairsState.value = syncRepository.getSyncPairs()
        historyLogsState.value = syncRepository.getHistoryLogs()
    }
    
    private fun scheduleBackgroundSync() {
        val pairs = syncRepository.getSyncPairs()
        if (pairs.isNotEmpty()) {
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AutoDropSync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        } else {
             WorkManager.getInstance(this).cancelUniqueWork("AutoDropSync")
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent == null) return
        lifecycleScope.launch {
            if (authManager.handleRedirect(intent)) {
                isLoggedInState.value = true
                fetchUserInfo()
                loadData()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentScreen: String,
    isLoggedIn: Boolean,
    userName: String,
    syncPairs: List<SyncPair>,
    historyLogs: List<SyncHistoryLog>,
    globalSyncStatus: String,
    onNavigate: (String) -> Unit,
    onConnect: () -> Unit,
    onLogout: () -> Unit,
    onAddPair: () -> Unit,
    onDeletePair: (String) -> Unit,
    onSyncAll: () -> Unit,
    onClearHistory: () -> Unit,
    onRefreshHistory: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isLoggedIn) "AutoDrop: $userName" else "AutoDrop") },
                actions = {
                    if (currentScreen == "history" && isLoggedIn) {
                        IconButton(onClick = onRefreshHistory) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isLoggedIn) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        selected = currentScreen == "home",
                        onClick = { onNavigate("home") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Info, contentDescription = "History") },
                        label = { Text("History") },
                        selected = currentScreen == "history",
                        onClick = { onNavigate("history") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (isLoggedIn && currentScreen == "home") {
                FloatingActionButton(onClick = onAddPair) {
                    Icon(Icons.Default.Add, contentDescription = "Add Sync Pair")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (!isLoggedIn) {
                Button(onClick = onConnect) {
                    Text("Connect Dropbox")
                }
            } else {
                if (currentScreen == "home") {
                    HomeScreen(syncPairs, globalSyncStatus, onSyncAll, onDeletePair, onLogout)
                } else {
                    HistoryScreen(historyLogs)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    syncPairs: List<SyncPair>,
    globalSyncStatus: String,
    onSyncAll: () -> Unit,
    onDeletePair: (String) -> Unit,
    onLogout: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Status Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Status: $globalSyncStatus", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onSyncAll) {
                    Text("Sync All")
                }
            }
        }

        // List of Pairs
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(syncPairs) { pair ->
                SyncPairItem(pair, onDelete = { onDeletePair(pair.id) })
            }
            if (syncPairs.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No sync folders configured. Tap + to add one.", color = Color.Gray)
                    }
                }
            }
        }
        
        Button(
            onClick = onLogout, 
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
        ) {
            Text("Disconnect Account")
        }
    }
}

@Composable
fun HistoryScreen(logs: List<SyncHistoryLog>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(logs) { log ->
            HistoryLogItem(log)
        }
        if (logs.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No history yet.", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun HistoryLogItem(log: SyncHistoryLog) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    val dateStr = dateFormat.format(Date(log.timestamp))
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = log.summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(text = dateStr, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (log.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = log.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SyncPairItem(pair: SyncPair, onDelete: () -> Unit) {
    
    fun getFriendlyLocal(uri: String): String {
        val path = android.net.Uri.parse(uri).path ?: return uri
        if (path.contains("tree/primary:")) {
            return path.replace("/tree/primary:", "/storage/emulated/0/")
        }
        return path
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Dropbox: ${pair.dropboxPath}", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Local: ${getFriendlyLocal(pair.localUri)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Last: ${pair.lastSyncStatus}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1976D2))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}
