package com.example.dns

import java.util.Locale

object DomainUtils {
    private val TWO_LEVEL_SUFFIXES =
        hashSetOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk",
            "com.bd", "net.bd", "org.bd", "edu.bd", "gov.bd", "ac.bd", "co.bd",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
            "co.in", "net.in", "org.in", "ac.in", "gov.in",
            "com.br", "org.br", "net.br", "gov.br",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "co.kr", "or.kr", "ne.kr", "go.kr",
            "co.nz", "org.nz", "net.nz", "govt.nz",
            "co.za", "org.za", "net.za", "gov.za",
            "com.sg", "org.sg", "edu.sg", "gov.sg",
            "com.my", "org.my", "net.my", "gov.my",
            "com.pk", "org.pk", "net.pk", "edu.pk",
            "com.tr", "org.tr", "net.tr", "gov.tr",
            "com.mx", "org.mx", "gob.mx",
            "com.ar", "org.ar", "gov.ar",
            "co.id", "or.id", "go.id", "ac.id",
            "com.vn", "net.vn", "org.vn", "gov.vn",
            "com.ph", "org.ph", "net.ph", "gov.ph",
            "com.tw", "org.tw", "net.tw", "edu.tw",
            "com.hk", "org.hk", "net.hk", "edu.hk",
            "co.th", "or.th", "ac.th", "go.th",
            "com.eg", "org.eg", "edu.eg",
            "com.ng", "org.ng", "gov.ng",
            "co.ke", "or.ke", "ac.ke",
            "com.sa", "net.sa", "org.sa",
            "co.il", "org.il", "ac.il",
            "eu.org",
        )

    private val UNSAFE_SUBSTRING_STEMS =
        listOf(
            "porn",
            "hentai",
            "nsfw",
            "erotica",
            "milf",
            "bdsm",
            "fetish",
            "dildo",
            "blowjob",
            "handjob",
            "rule34",
            "nhentai",
            "camgirl",
            "adultcam",
            "sexchat",
            "livesex",
            "sexcam",
            "sexvideo",
            "sextube",
            "sexshop",
            "sextoy",
            "chaturbate",
            "stripchat",
            "bongacams",
            "myfreecams",
            "brazzers",
            "bangbros",
            "xvideos",
            "xnxx",
            "xhamster",
            "redtube",
            "youporn",
            "spankbang",
            "fapello",
            "onlyfans",
        )

    private val UNSAFE_EXACT_TOKENS =
        hashSetOf(
            "sex",
            "xxx",
            "nude",
            "nudes",
            "boob",
            "boobs",
            "tits",
            "pussy",
            "cock",
            "dick",
            "cum",
            "slut",
            "whore",
            "escort",
            "escorts",
            "anal",
            "fuck",
        )

    fun normalizeFast(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * Approximate eTLD+1 registrable domain extractor.
     * Handles common 2-level public suffixes like co.uk, com.bd, com.au, co.jp, etc.
     */
    fun extractRegistrableDomain(rawDomain: String): String {
        val domain = normalizeFast(rawDomain) ?: return ""
        val parts = domain.split('.')
        val count = parts.size
        if (count <= 2) return domain

        val lastTwo = "${parts[count - 2]}.${parts[count - 1]}"
        return if (count >= 3 && TWO_LEVEL_SUFFIXES.contains(lastTwo)) {
            "${parts[count - 3]}.$lastTwo"
        } else {
            lastTwo
        }
    }

    fun containsAdultKeyword(normalizedDomain: String): Boolean {
        for (stem in UNSAFE_SUBSTRING_STEMS) {
            if (normalizedDomain.contains(stem)) {
                return true
            }
        }
        // Check token boundaries ('.', '-', '_') to avoid false positives like essex.co.uk
        val tokens = normalizedDomain.split('.', '-', '_')
        for (token in tokens) {
            if (UNSAFE_EXACT_TOKENS.contains(token)) {
                return true
            }
        }
        return false
    }
}
