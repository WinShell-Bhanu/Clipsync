package com.bunty.clipsync.db

import java.util.UUID

data class HistoryEntity(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val direction: String, // e.g., "Received from Mac", "Sent to Mac"
    val timestamp: Long,
    val isSuccess: Boolean,
    val type: String // "OTP", "Text", "Links", "Image", "File"
)
