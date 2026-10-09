package com.panomc.plugins.sociallogin.frontend

import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import io.vertx.core.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The words of the plugin's built-in pages. They are the `fallback` group of the plugin's own
 * `locales/<locale>.json`, English filling any gap, so the page speaks the site language without a second
 * set of files.
 */
internal object FallbackTexts {
    private const val DEFAULT_LOCALE = "en-US"
    private val LOCALE_PATTERN = Regex("""[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*""")

    private val cache = ConcurrentHashMap<String, Map<String, Any?>>()

    /** The texts of [group] (`magic-login`) for [locale]; an empty map when the plugin has none. */
    fun of(loader: ClassLoader, group: String, locale: String): Map<String, Any?> =
        cache.computeIfAbsent("$locale|$group") {
            val english = read(loader, DEFAULT_LOCALE, group)
            val own = if (locale == DEFAULT_LOCALE) emptyMap() else read(loader, locale, group).ifEmpty { read(loader, locale.substringBefore('-'), group) }

            english + own
        }

    private fun read(loader: ClassLoader, locale: String, group: String): Map<String, Any?> {
        if (!LOCALE_PATTERN.matches(locale)) {
            return emptyMap()
        }

        return try {
            loader.getOwnResourceStream("locales/$locale.json")?.use { stream ->
                JsonObject(stream.readBytes().toString(Charsets.UTF_8))
                    .getJsonObject("fallback")
                    ?.getJsonObject(group)
                    ?.map
            } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
