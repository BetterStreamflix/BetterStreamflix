package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.TvShow
import java.util.Locale

/**
 * Shared IPTV/live catalog metadata: country + language labels, genre IDs,
 * title-based country inference, and home/search helpers.
 */
object LiveCatalogMeta {

    private const val PAGE_SIZE = 48

    data class GenreKey(
        val kind: Kind,
        val value: String,
    ) {
        enum class Kind { COUNTRY, LANGUAGE, CATEGORY, LETTER, SERVER }
    }

    fun countryGenreId(code: String): String = "country:${normalizeCode(code)}"
    fun languageGenreId(code: String): String = "lang:${normalizeCode(code)}"
    fun categoryGenreId(slug: String): String = "cat:${slug.trim().lowercase(Locale.US)}"
    fun letterGenreId(letter: String): String = "letter:${letter.trim().uppercase(Locale.US).take(1)}"
    fun serverGenreId(server: String): String = "server:${server.trim().lowercase(Locale.US)}"

    fun parseGenreId(id: String): GenreKey? {
        val raw = id.trim()
        val sep = raw.indexOf(':')
        if (sep <= 0) return null
        val kind = raw.substring(0, sep).lowercase(Locale.US)
        val value = raw.substring(sep + 1).trim()
        if (value.isBlank()) return null
        return when (kind) {
            "country" -> GenreKey(GenreKey.Kind.COUNTRY, normalizeCode(value))
            "lang", "language" -> GenreKey(GenreKey.Kind.LANGUAGE, normalizeCode(value))
            "cat", "category", "sport", "group" -> GenreKey(GenreKey.Kind.CATEGORY, value.lowercase(Locale.US))
            "letter" -> GenreKey(GenreKey.Kind.LETTER, value.uppercase(Locale.US).take(1))
            "server" -> GenreKey(GenreKey.Kind.SERVER, value.lowercase(Locale.US))
            else -> null
        }
    }

    fun normalizeCode(code: String): String =
        code.trim().lowercase(Locale.US).removePrefix("xx").ifBlank { code.trim().lowercase(Locale.US) }

    fun countryName(code: String?): String {
        val c = normalizeCode(code.orEmpty())
        if (c.isBlank() || c == "?" || c == "xx") return "International"
        return COUNTRY_NAMES[c]
            ?: COUNTRY_NAMES[c.take(2)]
            ?: c.uppercase(Locale.US)
    }

    fun languageName(code: String?): String {
        val c = normalizeCode(code.orEmpty())
            .substringBefore('-')
            .ifBlank { return "Unknown" }
        return LANGUAGE_NAMES[c]
            ?: runCatching {
                Locale(c).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(c, true) }
            }.getOrNull()
            ?: c.uppercase(Locale.US)
    }

    fun countryFlagEmoji(code: String?): String {
        val c = normalizeCode(code.orEmpty()).take(2)
        if (c.length != 2 || !c.all { it in 'a'..'z' }) return "📺"
        val first = Character.codePointAt(c.uppercase(Locale.US), 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(c.uppercase(Locale.US), 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun overview(
        title: String,
        country: String? = null,
        language: String? = null,
        group: String? = null,
        quality: String? = null,
        extra: String? = null,
    ): String = buildString {
        append(title)
        val bits = buildList {
            country?.takeIf { it.isNotBlank() && it != "?" }?.let { add(countryName(it)) }
            language?.takeIf { it.isNotBlank() }?.let { add(languageName(it)) }
            group?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar { ch -> ch.uppercase() }) }
            quality?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
        }
        if (bits.isNotEmpty()) {
            append("\n")
            append(bits.joinToString(" · "))
        }
        extra?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
    }

    /**
     * Infer ISO-ish country code from DaddyLive / free-form channel titles
     * ("ESPN USA", "Sky Sports UK", "Canal+ France", …).
     */
    fun inferCountryFromTitle(title: String): String? {
        val t = title.lowercase(Locale.US)
            .replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (t.isBlank()) return null
        for ((needle, code) in TITLE_COUNTRY_SUFFIXES) {
            if (t == needle || t.endsWith(" $needle") || t.endsWith("($needle)") ||
                t.contains(" $needle ") || t.endsWith("-$needle") || t.endsWith(" $needle hd")
            ) {
                return code
            }
        }
        // trailing 2-letter token: "bein sports fr"
        val last = t.substringAfterLast(' ')
        if (last.length == 2 && last.all { it.isLetter() } && last in COUNTRY_NAMES) {
            return last
        }
        return null
    }

    fun pageShows(all: List<TvShow>, page: Int, pageSize: Int = PAGE_SIZE): List<TvShow> {
        val start = (page - 1).coerceAtLeast(0) * pageSize
        if (start >= all.size) return emptyList()
        return all.drop(start).take(pageSize)
    }

    fun <T> pageItems(all: List<T>, page: Int, pageSize: Int = PAGE_SIZE): List<T> {
        val start = (page - 1).coerceAtLeast(0) * pageSize
        if (start >= all.size) return emptyList()
        return all.drop(start).take(pageSize)
    }

    fun countryCategories(
        byCountry: Map<String, List<TvShow>>,
        limitCountries: Int = 12,
        perCountry: Int = 8,
        titlePrefix: String = "",
    ): List<Category> {
        return byCountry.entries
            .filter { it.key.isNotBlank() && it.key != "?" }
            .sortedByDescending { it.value.size }
            .take(limitCountries)
            .mapNotNull { (code, shows) ->
                val visible = shows.take(perCountry)
                val label = buildString {
                    append(countryFlagEmoji(code))
                    append(' ')
                    if (titlePrefix.isNotBlank()) {
                        append(titlePrefix)
                        append(' ')
                    }
                    append(countryName(code))
                    append(" (")
                    // Show the shelf size users actually see; note remainder when capped.
                    append(visible.size)
                    if (shows.size > visible.size) {
                        append('+')
                    }
                    append(')')
                }
                Category(label, visible).takeIf { it.list.isNotEmpty() }
            }
    }

    fun languageCategories(
        byLanguage: Map<String, List<TvShow>>,
        limit: Int = 10,
        perLang: Int = 8,
    ): List<Category> {
        return byLanguage.entries
            .filter { it.key.isNotBlank() && it.key != "?" }
            .sortedByDescending { it.value.size }
            .take(limit)
            .mapNotNull { (code, shows) ->
                val visible = shows.take(perLang)
                val countLabel =
                    if (shows.size > visible.size) "${visible.size}+" else "${visible.size}"
                Category(
                    "🗣 ${languageName(code)} ($countLabel)",
                    visible,
                ).takeIf { it.list.isNotEmpty() }
            }
    }

    fun genreBrowseResults(
        query: String,
        countries: Collection<String>,
        languages: Collection<String> = emptyList(),
        categories: Collection<String> = emptyList(),
    ): List<Genre> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = mutableListOf<Genre>()
        countries.distinct().forEach { code ->
            val name = countryName(code)
            if (name.contains(q, true) || code.contains(q, true)) {
                out += Genre(
                    id = countryGenreId(code),
                    name = "${countryFlagEmoji(code)} $name",
                )
            }
        }
        languages.distinct().forEach { code ->
            val name = languageName(code)
            if (name.contains(q, true) || code.contains(q, true)) {
                out += Genre(id = languageGenreId(code), name = "🗣 $name")
            }
        }
        categories.distinct().forEach { slug ->
            val label = slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
            if (label.contains(q, true) || slug.contains(q, true)) {
                out += Genre(id = categoryGenreId(slug), name = "📂 $label")
            }
        }
        return out.take(24)
    }

    /** Priority country packs for Famelack / CDN catalogs. */
    val PRIORITY_COUNTRIES = listOf(
        "us", "gb", "uk", "de", "fr", "it", "es", "ca", "au", "tr",
        "nl", "pl", "br", "mx", "ar", "in", "pt", "se", "dk", "no",
        "be", "at", "ch", "ie", "nz", "gr", "ro", "bg", "cz", "sk",
        "hu", "hr", "rs", "il", "ae", "sa", "qa", "jp", "kr", "cn",
    )

    private val TITLE_COUNTRY_SUFFIXES = listOf(
        "united states" to "us", "usa" to "us", " us" to "us",
        "united kingdom" to "uk", "uk" to "uk", " britain" to "uk", " england" to "uk",
        "canada" to "ca", " ca" to "ca",
        "australia" to "au", " au" to "au",
        "germany" to "de", "deutschland" to "de", " de" to "de",
        "france" to "fr", " fr" to "fr", " afrique" to "fr",
        "italy" to "it", "italia" to "it", " it" to "it",
        "spain" to "es", "españa" to "es", " es" to "es",
        "portugal" to "pt", " pt" to "pt",
        "netherlands" to "nl", "holland" to "nl", " nl" to "nl",
        "belgium" to "be", " be" to "be",
        "austria" to "at", " at" to "at",
        "switzerland" to "ch", " ch" to "ch",
        "poland" to "pl", " pl" to "pl",
        "sweden" to "se", " se" to "se",
        "denmark" to "dk", " dk" to "dk",
        "norway" to "no", " no" to "no",
        "finland" to "fi", " fi" to "fi",
        "ireland" to "ie", " ie" to "ie",
        "greece" to "gr", " gr" to "gr",
        "turkey" to "tr", "türkiye" to "tr", " tr" to "tr",
        "bulgaria" to "bg", " bg" to "bg",
        "romania" to "ro", " ro" to "ro",
        "serbia" to "rs", " rs" to "rs",
        "croatia" to "hr", " hr" to "hr",
        "slovakia" to "sk", " sk" to "sk",
        "czech" to "cz", "czechia" to "cz", " cz" to "cz",
        "hungary" to "hu", " hu" to "hu",
        "israel" to "il", " il" to "il",
        "india" to "in", " in" to "in",
        "brazil" to "br", "brasil" to "br", " br" to "br",
        "mexico" to "mx", " mx" to "mx",
        "argentina" to "ar", " ar" to "ar",
        "chile" to "cl", " cl" to "cl",
        "uruguay" to "uy",
        "new zealand" to "nz", " nz" to "nz",
        "south africa" to "za",
        "russia" to "ru", " ru" to "ru",
        "ukraine" to "ua",
        "arabic" to "sa", "saudi" to "sa", "arabia" to "sa",
        "uae" to "ae", "emirates" to "ae",
        "qatar" to "qa", " qa" to "qa",
        "cyprus" to "cy", " cy" to "cy",
        "japan" to "jp", "korea" to "kr", "china" to "cn",
        "latino" to "mx", "latam" to "mx",
    ).sortedByDescending { it.first.length }

    private val COUNTRY_NAMES = mapOf(
        "us" to "United States", "usa" to "United States",
        "gb" to "United Kingdom", "uk" to "United Kingdom",
        "de" to "Germany", "fr" to "France", "it" to "Italy", "es" to "Spain",
        "pt" to "Portugal", "nl" to "Netherlands", "be" to "Belgium",
        "at" to "Austria", "ch" to "Switzerland", "pl" to "Poland",
        "se" to "Sweden", "dk" to "Denmark", "no" to "Norway", "fi" to "Finland",
        "ie" to "Ireland", "gr" to "Greece", "tr" to "Turkey", "bg" to "Bulgaria",
        "ro" to "Romania", "rs" to "Serbia", "hr" to "Croatia", "sk" to "Slovakia",
        "cz" to "Czechia", "hu" to "Hungary", "il" to "Israel", "in" to "India",
        "br" to "Brazil", "mx" to "Mexico", "ar" to "Argentina", "cl" to "Chile",
        "uy" to "Uruguay", "ca" to "Canada", "au" to "Australia", "nz" to "New Zealand",
        "za" to "South Africa", "ru" to "Russia", "ua" to "Ukraine",
        "sa" to "Saudi Arabia", "ae" to "United Arab Emirates", "qa" to "Qatar",
        "cy" to "Cyprus", "jp" to "Japan", "kr" to "South Korea", "cn" to "China",
        "pk" to "Pakistan", "bd" to "Bangladesh", "th" to "Thailand", "vn" to "Vietnam",
        "id" to "Indonesia", "my" to "Malaysia", "ph" to "Philippines", "sg" to "Singapore",
        "eg" to "Egypt", "ma" to "Morocco", "ng" to "Nigeria", "ke" to "Kenya",
        "co" to "Colombia", "pe" to "Peru", "ve" to "Venezuela", "ec" to "Ecuador",
        "bo" to "Bolivia", "cr" to "Costa Rica", "do" to "Dominican Republic",
        "pr" to "Puerto Rico", "cu" to "Cuba", "gt" to "Guatemala",
        "hk" to "Hong Kong", "tw" to "Taiwan", "mo" to "Macau",
        "lu" to "Luxembourg", "mt" to "Malta", "is" to "Iceland", "ee" to "Estonia",
        "lv" to "Latvia", "lt" to "Lithuania", "si" to "Slovenia", "ba" to "Bosnia",
        "mk" to "North Macedonia", "al" to "Albania", "me" to "Montenegro",
        "ge" to "Georgia", "am" to "Armenia", "az" to "Azerbaijan", "by" to "Belarus",
        "kz" to "Kazakhstan", "uz" to "Uzbekistan", "ir" to "Iran", "iq" to "Iraq",
        "kw" to "Kuwait", "bh" to "Bahrain", "om" to "Oman", "jo" to "Jordan",
        "lb" to "Lebanon", "sy" to "Syria", "ye" to "Yemen", "ps" to "Palestine",
        "tn" to "Tunisia", "dz" to "Algeria", "ly" to "Libya", "sd" to "Sudan",
        "et" to "Ethiopia", "gh" to "Ghana", "ci" to "Ivory Coast", "sn" to "Senegal",
        "cm" to "Cameroon", "ug" to "Uganda", "tz" to "Tanzania", "zw" to "Zimbabwe",
        "ao" to "Angola", "mz" to "Mozambique", "na" to "Namibia",
        "lk" to "Sri Lanka", "np" to "Nepal", "mm" to "Myanmar", "kh" to "Cambodia",
        "la" to "Laos", "mn" to "Mongolia", "af" to "Afghanistan",
        "xx" to "International", "int" to "International",
    )

    private val LANGUAGE_NAMES = mapOf(
        "eng" to "English", "en" to "English",
        "deu" to "German", "de" to "German", "ger" to "German",
        "fra" to "French", "fr" to "French", "fre" to "French",
        "spa" to "Spanish", "es" to "Spanish",
        "ita" to "Italian", "it" to "Italian",
        "por" to "Portuguese", "pt" to "Portuguese",
        "nld" to "Dutch", "nl" to "Dutch", "dut" to "Dutch",
        "pol" to "Polish", "pl" to "Polish",
        "swe" to "Swedish", "sv" to "Swedish",
        "dan" to "Danish", "da" to "Danish",
        "nor" to "Norwegian", "no" to "Norwegian", "nb" to "Norwegian",
        "fin" to "Finnish", "fi" to "Finnish",
        "tur" to "Turkish", "tr" to "Turkish",
        "rus" to "Russian", "ru" to "Russian",
        "ara" to "Arabic", "ar" to "Arabic",
        "heb" to "Hebrew", "he" to "Hebrew",
        "hin" to "Hindi", "hi" to "Hindi",
        "ben" to "Bengali", "bn" to "Bengali",
        "tam" to "Tamil", "ta" to "Tamil",
        "tel" to "Telugu", "te" to "Telugu",
        "urd" to "Urdu", "ur" to "Urdu",
        "zho" to "Chinese", "zh" to "Chinese", "chi" to "Chinese",
        "jpn" to "Japanese", "ja" to "Japanese",
        "kor" to "Korean", "ko" to "Korean",
        "tha" to "Thai", "th" to "Thai",
        "vie" to "Vietnamese", "vi" to "Vietnamese",
        "ces" to "Czech", "cs" to "Czech",
        "slk" to "Slovak", "sk" to "Slovak",
        "hun" to "Hungarian", "hu" to "Hungarian",
        "ron" to "Romanian", "ro" to "Romanian",
        "bul" to "Bulgarian", "bg" to "Bulgarian",
        "hrv" to "Croatian", "hr" to "Croatian",
        "srp" to "Serbian", "sr" to "Serbian",
        "ell" to "Greek", "el" to "Greek", "gre" to "Greek",
        "ukr" to "Ukrainian", "uk" to "Ukrainian",
        "cat" to "Catalan", "ca" to "Catalan",
        "fas" to "Persian", "fa" to "Persian",
        "kaz" to "Kazakh", "kk" to "Kazakh",
        "uzb" to "Uzbek", "uz" to "Uzbek",
        "mya" to "Burmese", "my" to "Burmese",
        "mon" to "Mongolian", "mn" to "Mongolian",
        "pus" to "Pashto", "ps" to "Pashto",
        "div" to "Dhivehi", "dv" to "Dhivehi",
        "sgn" to "Sign Language",
        "bho" to "Bhojpuri",
        "kan" to "Kannada", "kn" to "Kannada",
    )
}
