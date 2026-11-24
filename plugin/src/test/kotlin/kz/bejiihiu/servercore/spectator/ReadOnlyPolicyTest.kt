package kz.bejiihiu.servercore.spectator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadOnlyPolicyTest {

    @Test
    fun `every click inside the viewer window is rejected`() {
        assertEquals(ViewerAction.CANCEL_EVENT, ReadOnlyPolicy.onClick(topInventoryIsViewer = true))
    }

    @Test
    fun `clicks outside the viewer window are none of our business`() {
        assertEquals(ViewerAction.ALLOW, ReadOnlyPolicy.onClick(topInventoryIsViewer = false))
    }

    @Test
    fun `a drag touching any viewer slot is rejected`() {
        assertEquals(ViewerAction.CANCEL_EVENT, ReadOnlyPolicy.onDrag(anyDraggedSlotIsViewer = true))
        assertEquals(ViewerAction.ALLOW, ReadOnlyPolicy.onDrag(anyDraggedSlotIsViewer = false))
    }

    @Test
    fun `creative clicks follow the same rule as normal clicks`() {
        assertEquals(ViewerAction.CANCEL_EVENT, ReadOnlyPolicy.onCreativeClick(topInventoryIsViewer = true))
        assertEquals(ViewerAction.ALLOW, ReadOnlyPolicy.onCreativeClick(topInventoryIsViewer = false))
    }

    @Test
    fun `closing the viewer window ends the viewer session`() {
        assertEquals(ViewerAction.END_VIEWER, ReadOnlyPolicy.onClose(topInventoryIsViewer = true))
        assertEquals(ViewerAction.ALLOW, ReadOnlyPolicy.onClose(topInventoryIsViewer = false))
    }

    @Test
    fun `a foreign container may not open over an active viewer`() {
        assertEquals(ViewerAction.CANCEL_EVENT, ReadOnlyPolicy.onForeignOpen(viewerActive = true))
        assertEquals(ViewerAction.ALLOW, ReadOnlyPolicy.onForeignOpen(viewerActive = false))
    }
}
