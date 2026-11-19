package com.example.data.local

data class CustomRuleEntity(
    val domain: String,
    val isWhitelist: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)
