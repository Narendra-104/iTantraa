package org.coresense.itantra.stt

/**
 * Intelligent post-processing speech normalizer for offline English ASR.
 *
 * Wav2Vec2 CTC models often produce raw phonetic character representations
 * under real ambient microphone conditions. This normalizer maps phonetic variants,
 * removes CTC stutter/triple-letter artifacts, and performs fuzzy vocabulary alignment
 * against standard emergency, tactical, and common conversational English words.
 */
object EnglishSpeechNormalizer {

    // Common phonetic substitutions produced by acoustic CTC models
    private val phoneticMap = mapOf(
        "halp" to "help",
        "hilp" to "help",
        "ned" to "need",
        "nid" to "need",
        "needeed" to "needed",
        "plees" to "please",
        "plez" to "please",
        "plz" to "please",
        "ambyulans" to "ambulance",
        "ambulens" to "ambulance",
        "ambulanse" to "ambulance",
        "ambulanc" to "ambulance",
        "amblance" to "ambulance",
        "ambylance" to "ambulance",
        "hospitl" to "hospital",
        "haspital" to "hospital",
        "hospitel" to "hospital",
        "imergensi" to "emergency",
        "emergensy" to "emergency",
        "imergency" to "emergency",
        "emargency" to "emergency",
        "imergansi" to "emergency",
        "polees" to "police",
        "polece" to "police",
        "polis" to "police",
        "polise" to "police",
        "asistens" to "assistance",
        "asistance" to "assistance",
        "assistens" to "assistance",
        "evakuat" to "evacuate",
        "evakuashun" to "evacuation",
        "evacuashun" to "evacuation",
        "evacueshun" to "evacuation",
        "medisin" to "medicine",
        "medicin" to "medicine",
        "medicel" to "medical",
        "medcal" to "medical",
        "doktr" to "doctor",
        "docter" to "doctor",
        "injurd" to "injured",
        "injerd" to "injured",
        "trapt" to "trapped" ,
        "bilding" to "building",
        "bildng" to "building",
        "lokashun" to "location",
        "lokeshun" to "location",
        "sityueshun" to "situation",
        "situeshun" to "situation",
        "danjer" to "danger",
        "disastr" to "disaster",
        "watar" to "water",
        "wotr" to "water",
        "floood" to "flood",
        "flud" to "flood",
        "fiar" to "fire",
        "fyr" to "fire",
        "erjunt" to "urgent",
        "urgunt" to "urgent",
        "blud" to "blood",
        "oksijen" to "oxygen",
        "oxijen" to "oxygen",
        "brij" to "bridge",
        "bridj" to "bridge",
        "rivr" to "river",
        "sektur" to "sector",
        "sentr" to "center",
        "kal" to "call",
        "peepal" to "people",
        "peopl" to "people",
        "chaild" to "child",
        "wuman" to "woman",
        "relwey" to "railway",
        "brek" to "break",
        "brokn" to "broken",
        "kolaps" to "collapse",
        "sivil" to "civil",
        "difens" to "defence",
        "steshun" to "station",
        "veekl" to "vehicle",
        "vehikl" to "vehicle",
        "traffik" to "traffic",
        "blokt" to "blocked",
        "blok" to "block",
        "imediut" to "immediate",
        "imediate" to "immediate",
        "rekwest" to "request",
        "rekwesting" to "requesting",
        "kordinats" to "coordinates",
        "dispatht" to "dispatched",
        "dispatct" to "dispatched",
        "tim" to "team",
        "rut" to "route",
        "sain" to "sign",
        "kler" to "clear",
        "klose" to "close",
        "thiz" to "this",
        "thet" to "that",
        "whot" to "what",
        "wer" to "where",
        "wen" to "when",
        "hau" to "how",
        "yu" to "you",
        "yur" to "your",
        "giv" to "give",
        "kam" to "come",
        "hir" to "here",
        "ther" to "there",
        "nau" to "now",
        "kwik" to "quick",
        "kwick" to "quick",
        "mor" to "more"
    )

    // Canonical vocabulary for fuzzy alignment
    private val canonicalLexicon = listOf(
        "emergency", "ambulance", "hospital", "police", "medical", "doctor",
        "medicine", "assistance", "evacuation", "evacuate", "injured", "trapped",
        "building", "location", "situation", "danger", "disaster", "water",
        "flood", "fire", "please", "need", "help", "urgent", "blood",
        "oxygen", "bridge", "river", "sector", "center", "centre", "call",
        "people", "child", "children", "woman", "women", "railway", "train",
        "broken", "collapse", "collapsed", "civil", "defence", "station",
        "vehicle", "traffic", "blocked", "immediate", "immediately", "request",
        "requesting", "coordinates", "dispatched", "dispatch", "team", "route",
        "clear", "close", "closed", "injured", "alive", "supplies", "food",
        "shelter", "blankets", "first", "aid", "rescue", "rescued", "report",
        "reported", "here", "there", "where", "what", "when", "this", "that"
    )

    fun normalize(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. Clean extra spaces and lowercase
        val lower = rawText.trim().lowercase()

        // 2. Reduce repeated characters > 2 to maximum 2 (e.g., "neeeeed" -> "need", "heeeeelp" -> "help")
        var deduplicated = lower.replace(Regex("([a-z])\\1{2,}"), "$1$1")

        // Tactical emergency compound phrases
        deduplicated = deduplicated
            .replace(Regex("\\bmay\\s+day\\b"), "mayday")
            .replace(Regex("\\bs\\s+o\\s+s\\b"), "sos")

        // 3. Tokenize words
        val words = deduplicated.split(Regex("\\s+")).filter { it.isNotBlank() }

        val normalizedWords = words.map { word ->
            // Direct phonetic map lookup
            phoneticMap[word] ?: run {
                // If already a known canonical word, leave untouched
                if (canonicalLexicon.contains(word)) {
                    word
                } else if (word.length >= 4) {
                    // Check Levenshtein fuzzy distance against canonical lexicon
                    var bestMatch: String? = null
                    var minDistance = Int.MAX_VALUE
                    val maxAllowedDistance = if (word.length >= 7) 2 else 1

                    for (canonical in canonicalLexicon) {
                        val dist = levenshteinDistance(word, canonical)
                        if (dist <= maxAllowedDistance && dist < minDistance) {
                            minDistance = dist
                            bestMatch = canonical
                        }
                    }
                    bestMatch ?: word
                } else {
                    word
                }
            }
        }

        return normalizedWords.joinToString(" ")
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
