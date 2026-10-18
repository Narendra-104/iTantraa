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
        "hospatal" to "hospital",
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
        "dockter" to "doctor",
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
        "mor" to "more",
        "diffrent" to "different",
        "differant" to "different",
        "speach" to "speech",
        "spech" to "speech",
        "lisn" to "listen",
        "listn" to "listen",
        "spik" to "speak",
        "spikin" to "speaking",
        "spiking" to "speaking",
        "respnd" to "respond",
        "propely" to "properly",
        "proparly" to "properly",
        "mesij" to "message",
        "mesaj" to "message",
        "radil" to "radio"
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
        "clear", "close", "closed", "alive", "supplies", "food",
        "shelter", "blankets", "first", "aid", "rescue", "rescued", "report",
        "reported", "here", "there", "where", "what", "when", "this", "that",
        "different", "speech", "listen", "respond", "properly", "speaking",
        "hearing", "message", "contact", "support"
    )

    // Comprehensive standard English conversational vocabulary.
    // Words in this set MUST NEVER be mutated by fuzzy emergency matching.
    private val knownEnglishWords: Set<String> = buildSet {
        addAll(canonicalLexicon)
        // Common conversational pronouns and determiners
        addAll(listOf(
            "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself",
            "he", "him", "his", "himself", "she", "her", "hers", "herself",
            "it", "its", "itself", "we", "us", "our", "ours", "ourselves",
            "they", "them", "their", "theirs", "themselves",
            "a", "an", "the", "this", "that", "these", "those",
            "all", "any", "both", "each", "every", "few", "more", "most", "much",
            "many", "neither", "no", "none", "one", "other", "several", "some", "such"
        ))
        // Common verbs & auxiliary verbs
        addAll(listOf(
            "is", "am", "are", "was", "were", "be", "being", "been",
            "have", "has", "had", "having", "do", "does", "did", "doing", "done",
            "can", "could", "shall", "should", "will", "would", "may", "might", "must",
            "say", "says", "said", "saying", "speak", "speaks", "spoke", "spoken", "speaking", "speech",
            "tell", "tells", "told", "telling", "talk", "talks", "talked", "talking",
            "listen", "listens", "listened", "listening", "hear", "hears", "heard", "hearing",
            "ask", "asks", "asked", "asking", "answer", "answers", "answered", "answering",
            "go", "goes", "went", "gone", "going", "come", "comes", "came", "coming",
            "get", "gets", "got", "getting", "make", "makes", "made", "making",
            "take", "takes", "took", "taken", "taking", "see", "saw", "seen", "seeing",
            "look", "looks", "looked", "looking", "give", "gives", "gave", "given", "giving",
            "find", "finds", "found", "finding", "think", "thinks", "thought", "thinking",
            "know", "knows", "knew", "known", "knowing", "want", "wants", "wanted", "wanting",
            "need", "needs", "needed", "needing", "feel", "feels", "felt", "feeling",
            "try", "tries", "tried", "trying", "leave", "leaves", "left", "leaving",
            "call", "calls", "called", "calling", "keep", "keeps", "kept", "keeping",
            "hold", "holds", "held", "holding", "bring", "brings", "brought", "bringing",
            "send", "sends", "sent", "sending", "receive", "receives", "received", "receiving",
            "stand", "stands", "stood", "standing", "stay", "stays", "stayed", "staying",
            "wait", "waits", "waited", "waiting", "walk", "walks", "walked", "walking",
            "run", "runs", "ran", "running", "move", "moves", "moved", "moving",
            "live", "lives", "lived", "living", "work", "works", "worked", "working",
            "stop", "stops", "stopped", "stopping", "start", "starts", "started", "starting",
            "respond", "responds", "responded", "responding", "check", "checks", "checked", "checking"
        ))
        // Common conversational adjectives, adverbs, and conjunctions
        addAll(listOf(
            "good", "better", "best", "bad", "worse", "worst", "fine", "great", "well",
            "ok", "okay", "roger", "copy", "safe", "ready", "sound", "clear", "clean",
            "right", "wrong", "true", "false", "new", "old", "first", "last", "long",
            "short", "high", "low", "big", "small", "large", "little", "different", "same",
            "proper", "properly", "fast", "slow", "quick", "quickly", "hard", "easy",
            "early", "late", "now", "soon", "later", "today", "tonight", "tomorrow", "yesterday",
            "always", "never", "sometimes", "often", "usually", "again", "already", "still",
            "very", "too", "quite", "really", "almost", "just", "only", "also", "even",
            "here", "there", "where", "how", "when", "why", "what", "which", "who", "whom",
            "and", "but", "or", "so", "because", "if", "as", "than", "that", "though",
            "with", "without", "for", "from", "at", "by", "in", "on", "into", "to", "over", "under",
            "food", "water", "blanket", "line", "wire", "radio", "phone", "device", "battery"
        ))
    }

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
                // If already a known standard English word, NEVER corrupt it with emergency fuzzy match
                if (knownEnglishWords.contains(word)) {
                    word
                } else if (word.length >= 5) {
                    // Check Levenshtein fuzzy distance against canonical emergency lexicon ONLY for unknown non-dictionary words
                    var bestMatch: String? = null
                    var minDistance = Int.MAX_VALUE
                    val maxAllowedDistance = if (word.length >= 7) 2 else 1

                    for (canonical in canonicalLexicon) {
                        if (kotlin.math.abs(word.length - canonical.length) > maxAllowedDistance) continue
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
