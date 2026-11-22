package com.example

import android.app.Application
import com.example.data.local.DnsShieldDatabase
import com.example.data.local.ShieldPreferences
import com.example.data.repository.ShieldRepository
import com.example.dns.DomainFilterEngine
import com.example.dns.GeminiDomainClassifier
import com.example.dns.VerdictCache
import java.io.File

class DnsShieldApplication : Application() {
    lateinit var database: DnsShieldDatabase
        private set

    lateinit var preferences: ShieldPreferences
        private set

    lateinit var filterEngine: DomainFilterEngine
        private set

    lateinit var verdictCache: VerdictCache
        private set

    lateinit var repository: ShieldRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = DnsShieldDatabase.getInstance(this)
        preferences = ShieldPreferences(this)
        filterEngine = DomainFilterEngine()
        verdictCache = VerdictCache(storageFile = File(filesDir, "verdict_cache.json"))

        lateinit var repoRef: ShieldRepository
        val classifier =
            GeminiDomainClassifier(
                apiKeyProvider = { repoRef.getCurrentApiKey() },
                modelProvider = { repoRef.getCurrentModel() },
            )

        repoRef =
            ShieldRepository(
                context = this,
                database = database,
                preferences = preferences,
                filterEngine = filterEngine,
                verdictCache = verdictCache,
                classifier = classifier,
            )
        repository = repoRef
    }
}
