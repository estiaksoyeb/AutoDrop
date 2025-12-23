package io.github.estiaksoyeb.autodrop

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

enum class SyncMethod {
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
    TWO_WAY
}

data class SyncPair(
    val id: String = UUID.randomUUID().toString(),
    val localUri: String,
    val dropboxPath: String,
    val lastSyncStatus: String = "Idle",
    val isEnabled: Boolean = true,
    val syncMethod: SyncMethod = SyncMethod.UPLOAD_ONLY,
    val excludedPaths: List<String> = emptyList()
)

data class SyncHistoryLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val pairId: String, // Can be "GLOBAL" or specific pair ID
    val summary: String,
    val details: String = ""
)

class SyncRepository(context: Context) {
    private val prefs = context.getSharedPreferences("autodrop_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // --- PAIRS ---
    fun getSyncPairs(): List<SyncPair> {
        val json = prefs.getString("sync_pairs", null) ?: return emptyList()
        val type = object : TypeToken<List<SyncPair>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addSyncPair(pair: SyncPair) {
        val current = getSyncPairs().toMutableList()
        current.add(pair)
        savePairs(current)
    }

    fun removeSyncPair(id: String) {
        val current = getSyncPairs().toMutableList()
        current.removeAll { it.id == id }
        savePairs(current)
    }
    
    fun updateSyncStatus(id: String, status: String) {
        val current = getSyncPairs().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            current[index] = current[index].copy(lastSyncStatus = status)
            savePairs(current)
        }
    }

    private fun savePairs(list: List<SyncPair>) {
        val json = gson.toJson(list)
        prefs.edit().putString("sync_pairs", json).apply()
    }
    
    // --- HISTORY ---
    fun getHistoryLogs(): List<SyncHistoryLog> {
        val json = prefs.getString("sync_history", null) ?: return emptyList()
        val type = object : TypeToken<List<SyncHistoryLog>>() {}.type
        return gson.fromJson(json, type)
    }
    
    fun addLog(log: SyncHistoryLog) {
        val current = getHistoryLogs().toMutableList()
        // Keep only last 100 logs
        if (current.size >= 100) {
            current.removeAt(current.lastIndex)
        }
        current.add(0, log) // Add to top
        val json = gson.toJson(current)
        prefs.edit().putString("sync_history", json).apply()
    }
    
    fun clearHistory() {
        prefs.edit().remove("sync_history").apply()
    }
    
    fun clearAll() {
        prefs.edit().remove("sync_pairs").apply()
        prefs.edit().remove("sync_history").apply()
    }
}
