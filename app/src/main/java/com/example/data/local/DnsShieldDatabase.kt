package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DnsShieldDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    private val dbMutex = Mutex()
    private val logsFlow = MutableStateFlow<List<BlockedLogEntity>>(emptyList())
    private val rulesFlow = MutableStateFlow<List<CustomRuleEntity>>(emptyList())

    init {
        // Hydrate initial flows safely
        runCatching {
            logsFlow.value = queryRecentLogsSync()
            rulesFlow.value = queryAllRulesSync()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain TEXT NOT NULL,
                query_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_ts ON $TABLE_LOGS(timestamp DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RULES (
                domain TEXT PRIMARY KEY,
                is_whitelist INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RULES")
        onCreate(db)
    }

    fun observeRecentLogs(): Flow<List<BlockedLogEntity>> = logsFlow.asStateFlow()

    fun observeAllRules(): Flow<List<CustomRuleEntity>> = rulesFlow.asStateFlow()

    suspend fun getAllRulesOnce(): List<CustomRuleEntity> =
        withContext(Dispatchers.IO) { dbMutex.withLock { queryAllRulesSync() } }

    suspend fun insertAndTrimLog(entry: BlockedLogEntity) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    val values =
                        ContentValues().apply {
                            put("domain", entry.domain)
                            put("query_type", entry.queryType)
                            put("timestamp", entry.timestamp)
                        }
                    db.insert(TABLE_LOGS, null, values)
                    db.execSQL(
                        """
                        DELETE FROM $TABLE_LOGS
                        WHERE id NOT IN (
                            SELECT id FROM $TABLE_LOGS ORDER BY timestamp DESC, id DESC LIMIT $MAX_LOG_ENTRIES
                        )
                        """.trimIndent()
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                logsFlow.value = queryRecentLogsSync()
            }
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                writableDatabase.delete(TABLE_LOGS, null, null)
                logsFlow.value = emptyList()
            }
        }
    }

    suspend fun upsertRule(rule: CustomRuleEntity) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                val values =
                    ContentValues().apply {
                        put("domain", rule.domain)
                        put("is_whitelist", if (rule.isWhitelist) 1 else 0)
                        put("created_at", rule.createdAt)
                    }
                writableDatabase.insertWithOnConflict(
                    TABLE_RULES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                rulesFlow.value = queryAllRulesSync()
            }
        }
    }

    suspend fun deleteRuleByDomain(domain: String) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                writableDatabase.delete(TABLE_RULES, "domain = ?", arrayOf(domain))
                rulesFlow.value = queryAllRulesSync()
            }
        }
    }

    private fun queryRecentLogsSync(): List<BlockedLogEntity> {
        val result = ArrayList<BlockedLogEntity>(MAX_LOG_ENTRIES)
        readableDatabase
            .rawQuery(
                "SELECT id, domain, query_type, timestamp FROM $TABLE_LOGS ORDER BY timestamp DESC, id DESC LIMIT $MAX_LOG_ENTRIES",
                null,
            )
            .use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val domainIdx = cursor.getColumnIndexOrThrow("domain")
                val typeIdx = cursor.getColumnIndexOrThrow("query_type")
                val tsIdx = cursor.getColumnIndexOrThrow("timestamp")
                while (cursor.moveToNext()) {
                    result.add(
                        BlockedLogEntity(
                            id = cursor.getLong(idIdx),
                            domain = cursor.getString(domainIdx),
                            queryType = cursor.getString(typeIdx),
                            timestamp = cursor.getLong(tsIdx),
                        )
                    )
                }
            }
        return result
    }

    private fun queryAllRulesSync(): List<CustomRuleEntity> {
        val result = ArrayList<CustomRuleEntity>()
        readableDatabase
            .rawQuery(
                "SELECT domain, is_whitelist, created_at FROM $TABLE_RULES ORDER BY created_at DESC",
                null,
            )
            .use { cursor ->
                val domainIdx = cursor.getColumnIndexOrThrow("domain")
                val whiteIdx = cursor.getColumnIndexOrThrow("is_whitelist")
                val createdIdx = cursor.getColumnIndexOrThrow("created_at")
                while (cursor.moveToNext()) {
                    result.add(
                        CustomRuleEntity(
                            domain = cursor.getString(domainIdx),
                            isWhitelist = cursor.getInt(whiteIdx) != 0,
                            createdAt = cursor.getLong(createdIdx),
                        )
                    )
                }
            }
        return result
    }

    companion object {
        private const val DATABASE_NAME = "dns_shield.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_LOGS = "blocked_logs"
        private const val TABLE_RULES = "custom_rules"
        private const val MAX_LOG_ENTRIES = 200

        @Volatile private var instance: DnsShieldDatabase? = null

        fun getInstance(context: Context): DnsShieldDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: DnsShieldDatabase(context.applicationContext).also { instance = it }
                }
    }
}
