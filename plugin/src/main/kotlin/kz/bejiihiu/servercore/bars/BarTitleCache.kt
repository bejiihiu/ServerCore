package kz.bejiihiu.servercore.bars

import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.CachedTemplate
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.Component

/**
 * parsed bar titles, one per distinct template text.
 *
 * a bar line used to run the placeholder regex and the minimessage parser on
 * every tick; this keeps the parse and only drops the numbers in. entries are
 * keyed by the template itself, so a language switch or a push that rewrites
 * the copy simply resolves a different key, while a revision bump (or the cap)
 * drops the old trees instead of letting them pile up per push.
 *
 * the health colours stay outside the cache: a threshold change only moves the
 * mark, which is applied per render.
 *
 * reads come from region threads, so the map is concurrent and every cached tree
 * is immutable; a render hands out freshly built nodes and never touches a cached
 * one.
 */
class BarTitleCache(
    private val locale: LocaleService,
    private val parser: (String) -> Component = Theme::parse,
    private val limit: Int = 64,
) {

    private val titles = ConcurrentHashMap<String, CachedTemplate>()

    @Volatile
    private var revision: String? = null

    /** the title for a key and value set; a missing key renders as the key itself */
    fun component(key: String, values: Map<String, String> = emptyMap()): Component {
        dropStaleRevision()
        val template = locale.template(key) ?: key
        var cached = titles[template]
        if (cached == null) {
            cached = CachedTemplate.of(template, parser)
            if (titles.size >= limit) titles.clear()
            titles[template] = cached
        }
        return cached.render(values)
    }

    /**
     * a push replaces locale text wholesale, so trees parsed from the old text
     * are dead weight. reads stay correct either way (the key is the text), this
     * just keeps a busy channel from retaining one entry per revision.
     */
    private fun dropStaleRevision() {
        val current = locale.revision
        if (current == revision) return
        synchronized(this) {
            if (current == revision) return
            titles.clear()
            revision = current
        }
    }
}
