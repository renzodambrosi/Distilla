package io.github.renzodambrosi.distilla.core

/**
 * Offline language detection for news text, without libraries or models.
 *
 *  - Non-Latin alphabets are recognised by script (Greek, Cyrillic, Arabic, Hebrew,
 *    Chinese, Japanese, Korean, Thai, Hindi...), with a few letters to tell apart
 *    Russian / Ukrainian / Bulgarian / Serbian and Arabic / Persian.
 *  - Latin-alphabet languages are recognised by counting their most frequent short
 *    words ("the, and, of" / "il, che, della" / "der, und, nicht"...). On a news story
 *    these words are 30-50% of the text, so a few paragraphs are enough.
 */
object LanguageDetector {

    data class Result(
        /** ISO 639-1 code, e.g. "it", "en", "de". */
        val language: String,
        /** Share of words recognised as belonging to [language] (0..1); 1 for script detection. */
        val score: Double,
        /** How far ahead of the runner-up: 0 = tie, 1 = no competitor. */
        val margin: Double,
        val runnerUp: String? = null,
    ) {
        val confident: Boolean get() = margin >= 0.25 && score >= 0.05
    }

    private fun words(s: String) = s.split(' ').filter { it.isNotBlank() }.toSet()

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to words("the and of to is was that for with on as are be by this from have has had not but they which at it his her their were been will would an or who said also its more than about after when there can new one into"),
        "it" to words("il lo la gli le di che è e per una un non con del della dei delle nel nella sono anche come più ma alla al ha hanno questo questa stato era dopo essere tra fra ancora perché già sul sulla degli nei"),
        "de" to words("der die das und ist nicht mit sich des auf für im dem den ein eine einer zu von auch es wird wurde sind bei nach aus wie noch werden hat haben aber oder nur dass über vor man so"),
        "fr" to words("le la les et des est une un du que qui dans pour pas sur au aux ce cette sont avec plus par ont été mais il elle ses leur nous vous être fait comme aussi entre après"),
        "es" to words("el la los las y en que es del un una por con para no se su sus al lo como más pero fue ha han este esta son también entre desde sobre muy hasta cuando ya"),
        "pt" to words("o a os as e de do da dos das em que é um uma para com não no na nos por se mais foi como mas ao também são ser sua seu pelo pela entre está já quando"),
        "nl" to words("de het een en van is dat op te in niet zijn met voor er aan ook als bij door maar dit om naar ze heeft hebben wordt werd nog wel worden uit over"),
        "sv" to words("och att det som är en för på med av den till inte har jag de om ett var han men ska kan sig från efter också när eller vid hade"),
        "da" to words("og at det er en til på med som af den for ikke har de om et var han men skal kan sig fra efter også når eller ved havde blev"),
        "nb" to words("og er det som en til på med av ikke har de om et var han men skal kan seg fra etter også når eller ved hadde ble jeg"),
        "fi" to words("ja on ei se että oli hän mutta kuin myös tai joka ovat sen sitä hänen kun mitä vain jo niin nyt olla ole siitä mukaan"),
        "pl" to words("i w na z się nie to że do jest jak o po co tak za od ale przez jego czy są już było może tylko także który która które oraz będzie"),
        "cs" to words("a v se na je že to s z do o jako ale by pro jsou byl bylo není také který která které jeho podle již může tak při jen"),
        "ro" to words("și în de la a cu că nu pe din este o un mai care sunt pentru au fost ca ce dar sau acest această lui fi după"),
        "hu" to words("a az és hogy nem is egy van de meg el ez azt csak volt már mint ki még ha vagy lesz után szerint pedig így kell"),
        "tr" to words("ve bir bu da de için ile çok gibi daha olarak olan en ama ne değil var sonra kadar ise mi her şey göre"),
        "hr" to words("i je u na se da su za od a s ne to kao iz koji koja koje ali bio bila još više nakon prema samo već"),
        "ca" to words("els amb és més però també són va seva aquest aquesta dels pels això molt fins ara perquè hi ho aquí encara havia"),
    )

    private val OWNERS: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((lang, set) in STOPWORDS) for (w in set) getOrPut(w) { mutableListOf() }.add(lang)
    }
    private val WEIGHT: Map<String, Double> = OWNERS.mapValues { 1.0 / it.value.size }

    private val WORD = Regex("[\\p{L}'’-]+")
    private const val MAX_WORDS = 3000

    fun detect(text: String): Result? {
        if (text.isBlank()) return null
        detectByScript(text)?.let { return it }

        val tokens = WORD.findAll(text.lowercase())
            .map { it.value.trim('\'', '’', '-') }
            .filter { it.isNotEmpty() }
            .take(MAX_WORDS)
            .toList()
        if (tokens.size < 5) return null

        // A word shared by n languages counts 1/n for each of them, so words typical
        // of one language ("the", "und", "perquè") weigh more than shared ones ("de", "la").
        val hits = HashMap<String, Double>()
        for (t in tokens) {
            val w = WEIGHT[t] ?: continue
            for (lang in OWNERS.getValue(t)) hits[lang] = (hits[lang] ?: 0.0) + w
        }
        val ranked = hits.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        if (best.value < 2.0) return null
        val second = ranked.getOrNull(1)
        val margin = if (second == null) 1.0 else (best.value - second.value) / best.value
        return Result(best.key, best.value / tokens.size, margin, second?.key)
    }

    private fun detectByScript(text: String): Result? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var letters = 0
        val sample = text.take(20_000)
        var i = 0
        while (i < sample.length) {
            val cp = sample.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            val script = Character.UnicodeScript.of(cp)
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters == 0) return null
        val latin = counts[Character.UnicodeScript.LATIN] ?: 0
        if (latin * 2 >= letters) return null

        fun n(s: Character.UnicodeScript) = counts[s] ?: 0
        val kana = n(Character.UnicodeScript.HIRAGANA) + n(Character.UnicodeScript.KATAKANA)
        val han = n(Character.UnicodeScript.HAN)
        val lang = when {
            kana > 0 && kana * 20 >= kana + han -> "ja"
            han * 2 >= letters -> "zh"
            n(Character.UnicodeScript.HANGUL) * 2 >= letters -> "ko"
            n(Character.UnicodeScript.CYRILLIC) * 2 >= letters -> cyrillic(sample)
            n(Character.UnicodeScript.GREEK) * 2 >= letters -> "el"
            n(Character.UnicodeScript.ARABIC) * 2 >= letters ->
                if (sample.any { it in "پچژگ" }) "fa" else "ar"
            n(Character.UnicodeScript.HEBREW) * 2 >= letters -> "he"
            n(Character.UnicodeScript.THAI) * 2 >= letters -> "th"
            n(Character.UnicodeScript.DEVANAGARI) * 2 >= letters -> "hi"
            n(Character.UnicodeScript.GEORGIAN) * 2 >= letters -> "ka"
            n(Character.UnicodeScript.ARMENIAN) * 2 >= letters -> "hy"
            else -> return null
        }
        return Result(lang, 1.0, 1.0)
    }

    private fun cyrillic(s: String): String {
        val lower = s.lowercase()
        fun has(chars: String) = lower.count { it in chars }
        return when {
            has("іїєґ") > 3 -> "uk"
            has("ђћџј") > 3 -> "sr"
            has("ыэё") == 0 && has("ъ") > 3 -> "bg"
            else -> "ru"
        }
    }

    /**
     * Voice languages to try, best first: the language the text is written in,
     * then the one declared by the page, then the phone's.
     *
     * The page tag is preferred when it agrees with the text, because it carries the
     * region ("pt-BR", "en-GB"), and also when detection is unsure but the page
     * names one of the two closest candidates (e.g. Danish vs Norwegian).
     */
    fun voiceCandidates(detected: Result?, pageLang: String?, deviceLang: String): List<String> {
        val page = pageLang?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
        val pageBase = page?.substringBefore('-')?.lowercase()
        val out = mutableListOf<String>()

        if (detected != null) {
            val agrees = pageBase == detected.language
            when {
                agrees -> out += page!!
                detected.confident -> out += detected.language
                pageBase != null && pageBase == detected.runnerUp -> out += page!!
                else -> out += detected.language
            }
        }
        if (page != null) out += page
        if (detected != null) out += detected.language
        out += deviceLang

        return out.distinctBy { it.lowercase() }
    }
}
