package kz.bejiihiu.servercore.ui

import java.util.regex.Matcher
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style

/**
 * a locale template parsed once and rendered many times.
 *
 * the template is parsed with every {name} swapped for a one-char marker, so the
 * component tree keeps the styling that spans a placeholder (the `</secondary>`
 * after `{used}` closes a tag opened before it). a render then only drops the
 * value runs into that tree: no minimessage parse, no placeholder regex.
 *
 * values go in as component text, never as markup, so a dynamic string cannot
 * inject tags — the same guarantee the escaping substitute gives the plain path.
 * unknown names keep their literal `{name}`, like [Placeholders.substitute].
 */
class CachedTemplate private constructor(
    private val template: String,
    private val parsed: Component?,
    private val names: List<String>,
    private val missing: List<String>,
    private val marker: Char,
) {

    /** the component for one value set */
    fun render(values: Map<String, String>): Component {
        val root = parsed ?: return Theme.render(template, values)
        if (names.isEmpty()) return root
        val pieces = substitute(root, values) ?: return root
        return if (pieces.size == 1) pieces[0] else Component.text().append(pieces).build()
    }

    /**
     * a node with no marker anywhere in it is returned untouched (the caller
     * keeps the cached instance), so a render only allocates along the path to
     * the values that actually move.
     */
    private fun substitute(node: Component, values: Map<String, String>): List<Component>? {
        val children = node.children()
        val replacedChildren = if (children.isEmpty()) null else substituteChildren(children, values)

        if (node is TextComponent) {
            val content = node.content()
            if (content.indexOf(marker) >= 0) {
                // a marker can sit mid-content, so one cached node becomes several
                // siblings; the original children render after all of them
                val pieces = split(content, node.style(), values)
                val last = pieces[pieces.size - 1]
                val tail = replacedChildren ?: children
                val lastWithTail = if (tail.isEmpty()) last else last.children(tail)
                if (pieces.size == 1) return listOf(lastWithTail)
                val out = ArrayList<Component>(pieces.size + tail.size)
                out.addAll(pieces.subList(0, pieces.size - 1))
                out.add(lastWithTail)
                return out
            }
        }

        if (replacedChildren != null) return listOf(node.children(replacedChildren))
        return null
    }

    private fun substituteChildren(children: List<Component>, values: Map<String, String>): List<Component>? {
        var out: ArrayList<Component>? = null
        for (i in children.indices) {
            val replaced = substitute(children[i], values)
            if (out == null) {
                if (replaced == null) continue
                out = ArrayList(children.size + 2)
                out.addAll(children.subList(0, i))
            }
            if (replaced == null) out.add(children[i]) else out.addAll(replaced)
        }
        return out
    }

    /** splits one marked text run into literal runs and value runs, all in the node's style */
    private fun split(content: String, style: Style, values: Map<String, String>): List<Component> {
        val out = ArrayList<Component>(4)
        var from = 0
        while (true) {
            val open = content.indexOf(marker, from)
            if (open < 0) break
            val close = content.indexOf(marker, open + 1)
            // unreachable: of() only ever writes complete pairs
            if (close < 0) break
            if (open > from) out.add(Component.text(content.substring(from, open), style))
            var index = 0
            for (at in open + 1 until close) index = index * 10 + (content[at] - '0')
            out.add(Component.text(valueAt(index, values), style))
            from = close + 1
        }
        if (from < content.length) out.add(Component.text(content.substring(from), style))
        if (out.isEmpty()) out.add(Component.text("", style))
        return out
    }

    /** a name the template never got, or a value the caller has no entry for, keeps the token */
    private fun valueAt(index: Int, values: Map<String, String>): String {
        val name = names.getOrNull(index) ?: return ""
        return values[name] ?: missing[index]
    }

    companion object {

        // private use area: a marker never collides with locale copy in practice,
        // and a template that does carry the char just moves to the next one
        private val MARKERS = '\uE000'..'\uF8FF'

        /**
         * parses [template] once. [parser] is the markup entry point — the theme's
         * in production, a counting stub in tests.
         */
        fun of(template: String, parser: (String) -> Component): CachedTemplate {
            val marker = MARKERS.firstOrNull { !template.contains(it) } ?: MARKERS.first
            val names = ArrayList<String>(4)
            val marked = StringBuilder(template.length + 8)
            val matcher = Placeholders.TOKEN.matcher(template)
            while (matcher.find()) {
                names.add(matcher.group(1))
                matcher.appendReplacement(marked, Matcher.quoteReplacement("$marker${names.size - 1}$marker"))
            }
            matcher.appendTail(marked)
            val root = parser(marked.toString())
            return CachedTemplate(
                template = template,
                // declarations with their own text (translatables and friends) keep
                // their text outside children(), so only plain text takes the fast path
                parsed = root.takeIf { plainTextOnly(it) && markersIntact(it, names.size, marker) },
                names = names,
                missing = names.map { "{$it}" },
                marker = marker,
            )
        }

        private fun plainTextOnly(node: Component): Boolean =
            node is TextComponent && node.children().all(::plainTextOnly)

        /**
         * every marker has to come out as text. one that landed inside a tag
         * argument (`<click:open_url:'{link}'>` is a real template) is consumed by
         * the parser and would bake a marker into a url, so such a template keeps
         * the per-render path instead.
         */
        private fun markersIntact(root: Component, count: Int, marker: Char): Boolean {
            val found = markers(root, marker, ArrayList(count))
            if (found.size != count) return false
            val seen = IntArray(count)
            for (index in found) {
                if (index !in 0 until count || ++seen[index] != 1) return false
            }
            return true
        }

        private fun markers(node: Component, marker: Char, into: ArrayList<Int>): ArrayList<Int> {
            if (node is TextComponent) {
                val content = node.content()
                var open = content.indexOf(marker)
                while (open >= 0) {
                    val close = content.indexOf(marker, open + 1)
                    if (close < 0) break
                    var index = 0
                    for (at in open + 1 until close) index = index * 10 + (content[at] - '0')
                    into.add(index)
                    open = content.indexOf(marker, close + 1)
                }
            }
            node.children().forEach { markers(it, marker, into) }
            return into
        }
    }
}
