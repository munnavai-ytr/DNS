package com.example.dns

enum class DomainVerdict {
    SAFE,
    ADULT,
    UNSURE,
}

enum class BlockReason(val label: String) {
    BLOCKLIST("Blocklist"),
    KEYWORD("Keyword"),
    CUSTOM("Custom"),
    UPSTREAM_FILTER("Upstream filter"),
    AI("AI"),
}

enum class UnsurePolicy {
    ALLOW,
    BLOCK;

    companion object {
        fun fromStored(value: String?): UnsurePolicy =
            if (value == BLOCK.name) BLOCK else ALLOW
    }
}

data class ClassificationResult(
    val verdict: DomainVerdict,
    val confidence: Double,
    val errorMessage: String? = null,
)

interface DomainClassifier {
    suspend fun classify(domain: String): ClassificationResult
}
