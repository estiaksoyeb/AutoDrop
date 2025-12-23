package io.github.estiaksoyeb.autodrop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    
    private lateinit var authManager: AuthManager
    private lateinit var syncManager: SyncManager
    private lateinit var syncRepository: SyncRepository
    
    // State
    private var isLoggedInState = mutableStateOf(false)
    private var userNameState = mutableStateOf("")
    private var userEmailState = mutableStateOf("")
    private var storageTotalState = mutableLongStateOf(0L)
    private var storageUsedState = mutableLongStateOf(0L)
    
    private var syncPairsState = mutableStateOf<List<SyncPair>>(emptyList())
    private var historyLogsState = mutableStateOf<List<SyncHistoryLog>>(emptyList())
    
    // Navigation & Draft State
    private var currentScreen = mutableStateOf("home") // "home", "history", "setup", "settings", "accounts"
    private var draftSyncPair = mutableStateOf<SyncPair?>(null)
    private var isPickingRemote = mutableStateOf(false)

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
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    
                    // Update Draft
                    draftSyncPair.value?.let { currentDraft ->
                        draftSyncPair.value = currentDraft.copy(localUri = uri.toString())
                    }
                }
            }

            // Main UI Switcher
            if (isPickingRemote.value && isLoggedInState.value) {
                DropboxFolderPicker(
                    accessToken = authManager.getAccessToken() ?: "",
                    initialPath = draftSyncPair.value?.dropboxPath ?: "",
                    onFolderSelected = { folder ->
                         draftSyncPair.value?.let { currentDraft ->
                            draftSyncPair.value = currentDraft.copy(dropboxPath = folder.pathDisplay)
                        }
                        isPickingRemote.value = false
                    },
                    onCancel = { 
                        isPickingRemote.value = false 
                    }
                )
            } else if (currentScreen.value == "setup" && draftSyncPair.value != null) {
                SyncSetupScreen(
                    draftPair = draftSyncPair.value!!,
                    onUpdateDraft = { updated -> draftSyncPair.value = updated },
                    onPickLocal = { localFolderLauncher.launch(null) },
                    onPickRemote = { isPickingRemote.value = true },
                    onSave = {
                        draftSyncPair.value?.let { pair ->
                            syncRepository.removeSyncPair(pair.id)
                            syncRepository.addSyncPair(pair)
                            loadData()
                            scheduleBackgroundSync()
                        }
                        draftSyncPair.value = null
                        currentScreen.value = "home"
                    },
                    onCancel = {
                        draftSyncPair.value = null
                        currentScreen.value = "home"
                    }
                )
            } else {
                MainScreen(
                    currentScreen = currentScreen.value,
                    isLoggedIn = isLoggedInState.value,
                    userName = userNameState.value,
                    userEmail = userEmailState.value,
                    storageTotal = storageTotalState.longValue,
                    storageUsed = storageUsedState.longValue,
                    syncPairs = syncPairsState.value,
                    historyLogs = historyLogsState.value,
                    globalSyncStatus = syncStatus.value,
                    onNavigate = { currentScreen.value = it },
                    onConnect = { authManager.startAuthFlow(this) },
                    onLogout = { 
                        authManager.logout() 
                        isLoggedInState.value = false
                        userNameState.value = ""
                        userEmailState.value = ""
                        storageTotalState.longValue = 0L
                        storageUsedState.longValue = 0L
                        syncRepository.clearAll()
                        loadData()
                        WorkManager.getInstance(this).cancelUniqueWork("AutoDropSync")
                        currentScreen.value = "home"
                    },
                    onAddPair = { 
                        draftSyncPair.value = SyncPair(localUri = "", dropboxPath = "")
                        currentScreen.value = "setup"
                    },
                    onEditPair = { pair ->
                        draftSyncPair.value = pair
                        currentScreen.value = "setup"
                    },
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
            val token = authManager.getAccessToken() ?: return@launch
            val client = DropboxClient(token)
            
            val accountDetails = client.getCurrentAccountDetails()
            if (accountDetails != null) {
                userNameState.value = accountDetails.getJSONObject("name").getString("display_name")
                userEmailState.value = accountDetails.getString("email")
            }
            
            val spaceUsage = client.getSpaceUsage()
            if (spaceUsage != null) {
                storageUsedState.longValue = spaceUsage.getLong("used")
                val allocation = spaceUsage.getJSONObject("allocation")
                if (allocation.getString(".tag") == "individual") {
                    storageTotalState.longValue = allocation.getLong("allocated")
                }
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
    userEmail: String,
    storageTotal: Long,
    storageUsed: Long,
    syncPairs: List<SyncPair>,
    historyLogs: List<SyncHistoryLog>,
    globalSyncStatus: String,
    onNavigate: (String) -> Unit,
    onConnect: () -> Unit,
    onLogout: () -> Unit,
    onAddPair: () -> Unit,
    onEditPair: (SyncPair) -> Unit,
    onDeletePair: (String) -> Unit,
    onSyncAll: () -> Unit,
    onClearHistory: () -> Unit,
    onRefreshHistory: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    when(currentScreen) {
                        "settings" -> Text("Settings")
                        "accounts" -> Text("Account")
                        else -> Text("AutoDrop") 
                    }
                },
                navigationIcon = {
                    if (currentScreen == "settings" || currentScreen == "accounts") {
                        IconButton(onClick = { 
                            if (currentScreen == "accounts") onNavigate("settings") else onNavigate("home") 
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentScreen == "home" && isLoggedIn) {
                        IconButton(onClick = { onNavigate("settings") }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
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
            if (isLoggedIn && (currentScreen == "home" || currentScreen == "history")) {
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
            contentAlignment = Alignment.TopCenter
        ) {
            if (!isLoggedIn) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = onConnect) {
                        Text("Connect Dropbox")
                    }
                }
            } else {
                when (currentScreen) {
                    "home" -> HomeScreen(syncPairs, globalSyncStatus, onSyncAll, onEditPair, onDeletePair)
                    "history" -> HistoryScreen(historyLogs)
                    "settings" -> SettingsScreen(onNavigate)
                    "accounts" -> AccountsScreen(userName, userEmail, storageUsed, storageTotal, onLogout)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onNavigate("accounts") },
            elevation = CardDefaults.cardElevation(2.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Accounts", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun AccountsScreen(
    userName: String, 
    userEmail: String, 
    used: Long, 
    total: Long, 
    onLogout: () -> Unit
) {
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccountCircle, 
                        contentDescription = null, 
                        modifier = Modifier.size(50.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(userName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(userEmail, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Storage Usage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                
                val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFFEEEEEE)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatSize(used)} used", style = MaterialTheme.typography.bodySmall)
                    Text("${formatSize(total)} total", style = MaterialTheme.typography.bodySmall)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Disconnect Account", color = Color.White)
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
    onEditPair: (SyncPair) -> Unit,
    onDeletePair: (String) -> Unit
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
                SyncPairItem(pair, onClick = { onEditPair(pair) }, onDelete = { onDeletePair(pair.id) })
            }
            if (syncPairs.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No sync folders configured. Tap + to add one.", color = Color.Gray)
                    }
                }
            }
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
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateStr = dateFormat.format(Date(log.timestamp))
    
    val containerColor = when(log.type) {
        LogType.ERROR -> Color(0xFFFFEBEE)
        LogType.CONFLICT -> Color(0xFFFFF8E1)
        LogType.START, LogType.END -> Color(0xFFE3F2FD)
        else -> Color.White
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "[$dateStr]", 
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = log.message, 
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
            }
            if (!log.details.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = log.details, 
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = Color.Red,
                    modifier = Modifier.padding(start = 64.dp)
                )
            }
        }
    }
}

@Composable
fun SyncPairItem(pair: SyncPair, onClick: () -> Unit, onDelete: () -> Unit) {
    
    fun getFriendlyLocal(uri: String): String {
        val path = android.net.Uri.parse(uri).path ?: return uri
        if (path.contains("tree/primary:")) {
            return path.replace("/tree/primary:", "/storage/emulated/0/")
        }
        return path
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() },
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
                Text(text = "Method: ${pair.syncMethod}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(text = "Last: ${pair.lastSyncStatus}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1976D2))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}