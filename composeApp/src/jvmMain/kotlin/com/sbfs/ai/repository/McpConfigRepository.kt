package com.sbfs.ai.repository

import com.sbfs.ai.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class McpConfigRepository {
    private val configFile: File
        get() = File(File(System.getProperty("user.dir")), "ai_config.json")
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun getConfig(): Config = withContext(Dispatchers.IO) {
        if (!configFile.exists()) {
            return@withContext Config()
        }
        
        try {
            val content = configFile.readText()
            json.decodeFromString(Config.serializer(), content)
        } catch (e: Exception) {
            println("Ошибка чтения конфига: ${e.message}")
            Config()
        }
    }
}