package kz.bejiihiu.servercore.ui

import net.kyori.adventure.bossbar.BossBar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CountdownRendererTest {

    @Test
    fun `progress is a clean fraction`() {
        assertEquals(1.0f, CountdownRenderer.progress(300, 300))
        assertEquals(0.5f, CountdownRenderer.progress(150, 300))
        assertEquals(0.0f, CountdownRenderer.progress(0, 300))
    }

    @Test
    fun `progress never escapes the bar`() {
        assertEquals(1.0f, CountdownRenderer.progress(999, 300))
        assertEquals(0.0f, CountdownRenderer.progress(-5, 300))
        assertEquals(0.0f, CountdownRenderer.progress(0, 0))
    }

    @Test
    fun `bar stays calm until a quarter is left then goes red`() {
        assertEquals(BossBar.Color.YELLOW, CountdownRenderer.barColor(200, 300))
        assertEquals(BossBar.Color.RED, CountdownRenderer.barColor(60, 300))
    }

    @Test
    fun `title ticks climb from brass to danger`() {
        assertEquals(Theme.GOLD, CountdownRenderer.tickColor(5))
        assertEquals(Theme.WARN, CountdownRenderer.tickColor(3))
        assertEquals(Theme.DANGER, CountdownRenderer.tickColor(1))
    }

    @Test
    fun `clock formats loose minutes`() {
        assertEquals("5:00", CountdownRenderer.mmss(300))
        assertEquals("0:07", CountdownRenderer.mmss(7))
        assertEquals("0:00", CountdownRenderer.mmss(-1))
        assertEquals("12:34", CountdownRenderer.mmss(754))
    }
}
