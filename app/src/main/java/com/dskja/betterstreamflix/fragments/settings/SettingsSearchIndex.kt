package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.annotation.XmlRes
import org.xmlpull.v1.XmlPullParser

/**
 * Lightweight index over a settings XML tree. Each Preference inside a nested
 * PreferenceScreen is indexed with its owning screen key + display path so
 * search results can deep-link straight into the right section.
 */
object SettingsSearchIndex {

    private const val NS = "http://schemas.android.com/apk/res/android"

    data class Entry(
        val prefKey: String,
        val title: String,
        val summary: String,
        /** null = root screen */
        val screenKey: String?,
        /** Title of the owning nested screen (empty for root). */
        val screenTitle: String,
        /** e.g. "Content · TMDb" — breadcrumb shown under the result. */
        val path: String,
    ) {
        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return false
            return title.lowercase().contains(q) ||
                summary.lowercase().contains(q) ||
                prefKey.lowercase().replace('_', ' ').contains(q)
        }
    }

    fun index(context: Context, @XmlRes resId: Int): List<Entry> {
        val out = ArrayList<Entry>(256)
        val parser = context.resources.getXml(resId)
        val screenStack = ArrayDeque<Pair<String, String>>() // key → title
        var category: String? = null
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfterLast('.')) {
                        "PreferenceScreen" -> {
                            val key = attr(context, parser, "key").orEmpty()
                            val title = attr(context, parser, "title").orEmpty()
                            screenStack.addLast(key to title)
                        }
                        "PreferenceCategory" -> {
                            category = attr(context, parser, "title")?.ifBlank { null }
                        }
                        // Covers SwitchPreferenceCompat, custom FQCN prefs, etc.
                        // PreferenceScreen/Category are handled by earlier branches.
                        else -> if (parser.name.substringAfterLast('.')
                                .contains("Preference")
                        ) {
                            val key = attr(context, parser, "key")
                            val title = attr(context, parser, "title")
                            if (key != null && title != null) {
                                val summary = attr(context, parser, "summary").orEmpty()
                                val (screenKey, screenTitle) = screenStack.lastOrNull()
                                    ?: (null to null)
                                // Blank key = the document root screen.
                                val owningScreen = screenKey?.ifBlank { null }
                                val path = listOfNotNull(
                                    screenTitle?.takeIf { it.isNotBlank() },
                                    category,
                                ).joinToString(" · ")
                                out += Entry(
                                    prefKey = key,
                                    title = title,
                                    summary = summary,
                                    screenKey = owningScreen,
                                    screenTitle = screenTitle.orEmpty(),
                                    path = path.ifBlank { screenTitle.orEmpty() },
                                )
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name.substringAfterLast('.')) {
                        "PreferenceScreen" -> screenStack.removeLastOrNull()
                        "PreferenceCategory" -> category = null
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }

    /** Resolve `android:` attribute — handles both literals and @string refs. */
    private fun attr(
        context: Context,
        parser: XmlResourceParser,
        name: String,
    ): String? {
        val resId = parser.getAttributeResourceValue(NS, name, 0)
        if (resId != 0) {
            return runCatching { context.getString(resId) }.getOrNull()
        }
        return parser.getAttributeValue(NS, name)
    }
}
