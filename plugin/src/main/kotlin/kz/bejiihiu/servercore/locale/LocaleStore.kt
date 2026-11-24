package kz.bejiihiu.servercore.locale

import org.yaml.snakeyaml.Yaml

/**
 * an immutable set of locale entries parsed from one yaml document.
 * pure data — no bukkit imports, safe to build on any thread and swap in
 * atomically while players are being rendered to.
 */
class LocaleStore private constructor(private val entries: Map<String, String>) {

    /** raw miniMessage template for a key, or null when the key is missing */
    fun raw(key: String): String? = entries[key]

    fun has(key: String): Boolean = entries.containsKey(key)

    fun keys(): Set<String> = entries.keys

    val isEmpty: Boolean get() = entries.isEmpty()

    /** overlay on top of this store; overlay wins key by key */
    fun mergedWith(overlay: Map<String, String>): LocaleStore =
        of(entries + overlay)

    companion object {

        fun of(entries: Map<String, String>): LocaleStore = LocaleStore(entries)

        /**
         * parses a yaml document into a flat dotted-key map.
         * string scalars are kept verbatim (they are miniMessage templates),
         * lists are joined with newlines so multi-line templates stay
         * readable in yaml, other scalars become their string form.
         */
        fun parse(yamlContent: String): LocaleStore {
            val loaded: Any? = Yaml().load(yamlContent)
            val entries = LinkedHashMap<String, String>()
            flatten(loaded, "", entries)
            return LocaleStore(entries)
        }

        @Suppress("UNCHECKED_CAST")
        private fun flatten(node: Any?, prefix: String, into: LinkedHashMap<String, String>) {
            when (node) {
                is Map<*, *> -> for ((k, v) in node) {
                    val key = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
                    flatten(v, key, into)
                }
                is List<*> -> if (node.isNotEmpty()) {
                    into[prefix] = node.joinToString("\n") { scalar(it) }
                }
                is String -> if (prefix.isNotEmpty()) into[prefix] = node.trimEnd('\n', '\r')
                is Number, is Boolean -> if (prefix.isNotEmpty()) into[prefix] = node.toString()
                else -> {} // nulls and oddities are skipped, not stored
            }
        }

        private fun scalar(v: Any?): String = when (v) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> ""
        }
    }
}
