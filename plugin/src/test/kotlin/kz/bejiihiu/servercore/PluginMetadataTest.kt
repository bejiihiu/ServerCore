package kz.bejiihiu.servercore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.yaml.snakeyaml.Yaml

/**
 * Plugin metadata is yaml the server parses before any of our code runs: one
 * bad colon in a free-text field and the whole jar silently never loads. These
 * assertions come straight from that failure - they are the cheap guard that
 * keeps the build from shipping an unloadable plugin.
 */
class PluginMetadataTest {

    private fun metadata(): Map<*, *> {
        val raw = javaClass.classLoader.getResourceAsStream("paper-plugin.yml")
        assertNotNull(raw, "paper-plugin.yml missing from the jar")
        return Yaml().load<Map<*, *>>(raw.readBytes().decodeToString())
    }

    @Test
    fun `metadata parses and points at the real entry point`() {
        val yaml = metadata()
        assertEquals("ServerCore", yaml["name"])
        assertEquals("kz.bejiihiu.servercore.ServerCorePlugin", yaml["main"])
    }

    @Test
    fun `metadata keeps folia support and api version`() {
        val yaml = metadata()
        assertEquals(true, yaml["folia-supported"])
        assertEquals("26.3", yaml["api-version"])
    }

    @Test
    fun `packetevents stays a hard dependency`() {
        val server = (metadata()["dependencies"] as Map<*, *>)["server"] as Map<*, *>
        // the key is the plugin id, and paper matches it against the other
        // plugin's declared name character for character
        val packetEvents = server["packetevents"] as Map<*, *>
        assertEquals(true, packetEvents["required"])
        assertEquals("BEFORE", packetEvents["load"])
    }
}
