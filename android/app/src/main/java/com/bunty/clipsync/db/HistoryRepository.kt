package com.bunty.clipsync.db

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HistoryRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("clipsync_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyKey = "history_list"

    private val _allHistory = MutableStateFlow<List<HistoryEntity>>(loadHistory())
    val allHistory: StateFlow<List<HistoryEntity>> = _allHistory

    private fun loadHistory(): List<HistoryEntity> {
        val json = prefs.getString(historyKey, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryEntity>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<HistoryEntity>) {
        val json = gson.toJson(list)
        prefs.edit().putString(historyKey, json).apply()
        _allHistory.value = list
    }

    fun insert(entity: HistoryEntity) {
        val currentList = _allHistory.value.toMutableList()
        // Add to the top
        currentList.add(0, entity)
        // Keep max 100 items
        if (currentList.size > 100) {
            currentList.removeLast()
        }
        saveHistory(currentList)
    }

    fun clearAll() {
        saveHistory(emptyList())
    }

    fun addSent(content: String, type: String = "Text", isSuccess: Boolean = true) {
        insert(HistoryEntity(
            content = content,
            direction = "Sent to Mac",
            timestamp = System.currentTimeMillis(),
            isSuccess = isSuccess,
            type = type
        ))
    }

    fun addReceived(content: String, type: String = "Text", isSuccess: Boolean = true) {
        insert(HistoryEntity(
            content = content,
            direction = "Received from Mac",
            timestamp = System.currentTimeMillis(),
            isSuccess = isSuccess,
            type = type
        ))
    }

    companion object {
        @Volatile
        private var instance: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: HistoryRepository(context).also { instance = it }
            }
        }
    }
}
