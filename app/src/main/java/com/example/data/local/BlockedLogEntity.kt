package com.example.data.local

data class BlockedLogEntity(
    val id: Long = 0,
    val domain: String,
    val queryType: String,
    val reason: String = "Blocklist",
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String = "",
)
