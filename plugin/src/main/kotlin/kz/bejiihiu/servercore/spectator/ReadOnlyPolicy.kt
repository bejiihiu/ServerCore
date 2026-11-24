package kz.bejiihiu.servercore.spectator

/**
 * pure read-only decisions for the viewer window. the rule is absolute: while
 * the viewer holds the admin's open container, every inventory event inside
 * that window is rejected — no item may move into, out of or within the view
 * in either direction, including the admin's own inventory panel at the bottom
 * of the screen (a shift-click there would try to deposit a real item into the
 * backing container, which is how items get destroyed).
 *
 * nothing here touches bukkit; the glue listeners just apply the verdicts so
 * the decision logic stays unit-testable.
 */
enum class ViewerAction {
    /** cancel the event, the view is read-only */
    CANCEL_EVENT,

    /** the viewer session ends, clean up its state */
    END_VIEWER,

    /** not the viewer's business, let the event flow */
    ALLOW,
}

object ReadOnlyPolicy {

    /** any click inside the viewer window, top or bottom panel */
    fun onClick(topInventoryIsViewer: Boolean): ViewerAction =
        if (topInventoryIsViewer) ViewerAction.CANCEL_EVENT else ViewerAction.ALLOW

    /** drags can span both panels, so one viewer slot anywhere is enough */
    fun onDrag(anyDraggedSlotIsViewer: Boolean): ViewerAction =
        if (anyDraggedSlotIsViewer) ViewerAction.CANCEL_EVENT else ViewerAction.ALLOW

    /** creative-mode clicks bypass the normal click path, same verdict */
    fun onCreativeClick(topInventoryIsViewer: Boolean): ViewerAction =
        if (topInventoryIsViewer) ViewerAction.CANCEL_EVENT else ViewerAction.ALLOW

    /** the admin closed the window themselves */
    fun onClose(topInventoryIsViewer: Boolean): ViewerAction =
        if (topInventoryIsViewer) ViewerAction.END_VIEWER else ViewerAction.ALLOW

    /** something else wants to open a container over the viewer — keep the viewer, block the newcomer */
    fun onForeignOpen(viewerActive: Boolean): ViewerAction =
        if (viewerActive) ViewerAction.CANCEL_EVENT else ViewerAction.ALLOW
}
