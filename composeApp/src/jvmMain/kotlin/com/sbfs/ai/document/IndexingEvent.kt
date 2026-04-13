package com.sbfs.ai.document

sealed class IndexingEvent {
    data class FileStarted(val filename: String) : IndexingEvent()
    data class FileFinished(val filename: String) : IndexingEvent()
}
