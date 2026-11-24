package kz.bejiihiu.servercore.packet

import kz.bejiihiu.servercore.esp.ViewState

/**
 * what a specific viewer's client knows (or is allowed to know) about a target
 * entity. per-viewer on purpose: two clients looking at the same server entity
 * may see completely different things.
 *
 * the enum itself is the one canonical definition in the esp module; this
 * alias keeps the packet layer's naming. the sync layer's DEAD pin never
 * reaches the packet registry — a dead pair is applied as plain vanilla
 * (VISIBLE) because vanilla death rendering is exactly what the client gets.
 */
typealias EntityViewState = ViewState
