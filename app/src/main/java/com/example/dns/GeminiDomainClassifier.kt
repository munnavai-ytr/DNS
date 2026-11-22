package com.example.dns

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class GeminiDomainClassifier(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
) : DomainClassifier {

    override suspend fun classify(domain: String): ClassificationResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return ClassificationResult(
                verdict = DomainVerdict.UNSURE,
                confidence = 0.0,
                errorMessage = "Gemini API key is not set",
            )
        }
        val model = modelProvider().trim().ifEmpty { DEFAULT_MODEL }

        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { executeClassifyRequest(domain, apiKey, model) }
        }
            ?: ClassificationResult(
                verdict = DomainVerdict.UNSURE,
                confidence = 0.0,
                errorMessage = "Timed out after ${TOTAL_TIMEOUT_MS} ms",
            )
    }

    private fun executeClassifyRequest(
        domain: String,
        apiKey: String,
        model: String,
    ): ClassificationResult {
        var connection: HttpURLConnection? = null
        return try {
            val encodedModel = URLEncoder.encode(model, "UTF-8")
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent"
            val url = URL(endpoint)
            connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                }

            val prompt =
                "Classify the domain name \"$domain\" for adult/pornographic content safety. " +
                    "Return strict JSON: {\"verdict\":\"SAFE|ADULT|UNSURE\",\"confidence\":0.0-1.0}."

            val payload =
                JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put(
                                        "parts",
                                        JSONArray().apply {
                                            put(JSONObject().apply { put("text", prompt) })
                                        },
                                    )
                                }
                            )
                        },
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseMimeType", "application/json")
                            put("temperature", 0.0)
                        },
                    )
                }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val apiMsg =
                    runCatching {
                            JSONObject(bodyText).optJSONObject("error")?.optString("message")
                        }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                return ClassificationResult(
                    verdict = DomainVerdict.UNSURE,
                    confidence = 0.0,
                    errorMessage = "HTTP $code: ${apiMsg ?: "Request failed"}",
                )
            }

            parseGeminiResponse(bodyText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ClassificationResult(
                verdict = DomainVerdict.UNSURE,
                confidence = 0.0,
                errorMessage = e.message ?: e::class.java.simpleName,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    internal fun parseGeminiResponse(bodyText: String): ClassificationResult {
        return try {
            val root = JSONObject(bodyText)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
            if (rawText.isEmpty()) {
                return ClassificationResult(
                    verdict = DomainVerdict.UNSURE,
                    confidence = 0.0,
                    errorMessage = "Empty model response",
                )
            }

            val cleanedJson =
                rawText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val verdictObj = JSONObject(cleanedJson)
            val verdictRaw = verdictObj.optString("verdict", "UNSURE").trim().uppercase()
            val verdict =
                when (verdictRaw) {
                    "SAFE" -> DomainVerdict.SAFE
                    "ADULT" -> DomainVerdict.ADULT
                    else -> DomainVerdict.UNSURE
                }
            val confidence = verdictObj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            ClassificationResult(verdict = verdict, confidence = confidence, errorMessage = null)
        } catch (e: Exception) {
            ClassificationResult(
                verdict = DomainVerdict.UNSURE,
                confidence = 0.0,
                errorMessage = "Invalid JSON response: ${e.message ?: "parse error"}",
            )
        }
    }

    data class VisualClassificationResult(
        val adult: Boolean,
        val confidence: Double,
        val errorMessage: String? = null,
    )

    suspend fun classifyImageBase64(base64Jpeg: String): VisualClassificationResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty() || base64Jpeg.isBlank()) {
            return VisualClassificationResult(adult = false, confidence = 0.0, errorMessage = "Skipped")
        }
        val model = modelProvider().trim().ifEmpty { DEFAULT_MODEL }

        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { executeVisualRequest(base64Jpeg, apiKey, model) }
        }
            ?: VisualClassificationResult(
                adult = false,
                confidence = 0.0,
                errorMessage = "Timed out after ${TOTAL_TIMEOUT_MS} ms",
            )
    }

    private fun executeVisualRequest(
        base64Jpeg: String,
        apiKey: String,
        model: String,
    ): VisualClassificationResult {
        var connection: HttpURLConnection? = null
        return try {
            val encodedModel = URLEncoder.encode(model, "UTF-8")
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent"
            val url = URL(endpoint)
            connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                }

            val prompt =
                "Classify whether this mobile screen thumbnail shows adult, pornographic, or sexually explicit content. " +
                    "Return strict JSON: {\"adult\":true|false,\"confidence\":0.0-1.0}."

            val payload =
                JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put(
                                        "parts",
                                        JSONArray().apply {
                                            put(JSONObject().apply { put("text", prompt) })
                                            put(
                                                JSONObject().apply {
                                                    put(
                                                        "inline_data",
                                                        JSONObject().apply {
                                                            put("mime_type", "image/jpeg")
                                                            put("data", base64Jpeg)
                                                        },
                                                    )
                                                }
                                            )
                                        },
                                    )
                                }
                            )
                        },
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseMimeType", "application/json")
                            put("temperature", 0.0)
                        },
                    )
                }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return VisualClassificationResult(
                    adult = false,
                    confidence = 0.0,
                    errorMessage = "HTTP $code",
                )
            }

            parseVisualResponse(bodyText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VisualClassificationResult(
                adult = false,
                confidence = 0.0,
                errorMessage = e.message ?: e::class.java.simpleName,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    internal fun parseVisualResponse(bodyText: String): VisualClassificationResult {
        return try {
            val root = JSONObject(bodyText)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
            if (rawText.isEmpty()) {
                return VisualClassificationResult(adult = false, confidence = 0.0, errorMessage = "Empty")
            }
            val cleanedJson =
                rawText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val verdictObj = JSONObject(cleanedJson)
            val adult = verdictObj.optBoolean("adult", false)
            val confidence = verdictObj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            VisualClassificationResult(adult = adult, confidence = confidence, errorMessage = null)
        } catch (e: Exception) {
            VisualClassificationResult(
                adult = false,
                confidence = 0.0,
                errorMessage = e.message ?: "Parse error",
            )
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val GEMINI_API_HOST = "generativelanguage.googleapis.com"
        private const val TOTAL_TIMEOUT_MS = 3000L
        private const val HTTP_TIMEOUT_MS = 2800
    }
}
