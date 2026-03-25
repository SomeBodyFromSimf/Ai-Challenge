package com.sbfs.ai.repository

import com.sbfs.ai.data.Model
import com.sbfs.ai.db.AiChallengeDb

class ModelRepository(
    db: AiChallengeDb,
) {
    private val modelQueries = db.modelQueries

    fun getModels(): List<Model> {
        return modelQueries.getAll().executeAsList().map { messageEntity ->
            Model(
                id = messageEntity.id,
                name = messageEntity.name,
                contextLength = messageEntity.contextLength,
            )
        }
    }

    fun getModelByName(name: String): Model? {
        return modelQueries.getByName(name).executeAsOneOrNull()?.let { messageEntity ->
            Model(
                id = messageEntity.id,
                name = messageEntity.name,
                contextLength = messageEntity.contextLength,
            )
        }
    }
}