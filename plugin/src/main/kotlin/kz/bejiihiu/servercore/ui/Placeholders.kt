package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * placeholder substitution for locale templates.
 *
 * values are MiniMessage-escaped before injection, so any dynamic text
 * (player names, codes, backend strings) stays literal even when a pushed
 * locale or a malicious value carries markup like <gold> or <click>.
 */
object Placeholders {

    // named tokens only: {code}, {player}, {time}. no positional args —
    // locales ship over the wire and need readable keys. shared with the
    // cached renderer, so both read the same grammar
    internal val TOKEN: Pattern = Pattern.compile("\\{([A-Za-z0-9_.]+)}")

    private val mini = MiniMessage.miniMessage()

    /** escapes a value so miniMessage renders it literally */
    fun escape(value: String): String = mini.escapeTags(value)

    /**
     * replaces every {name} with the escaped value for that name.
     * unknown tokens are left in place — a missing value should be visible,
     * not silently swallowed.
     */
    fun substitute(template: String, values: Map<String, String> = emptyMap()): String {
        if (values.isEmpty()) return template
        val m = TOKEN.matcher(template)
        val out = StringBuffer(template.length + 16)
        while (m.find()) {
            val value = values[m.group(1)]?.let(::escape) ?: continue
            // quoteReplacement keeps $ and \ in values literal
            m.appendReplacement(out, Matcher.quoteReplacement(value))
        }
        m.appendTail(out)
        return out.toString()
    }
}
