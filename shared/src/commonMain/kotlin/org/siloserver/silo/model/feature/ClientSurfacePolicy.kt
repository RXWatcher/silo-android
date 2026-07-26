package org.siloserver.silo.model.feature

/**
 * Client-side exposure switches for implemented surfaces that are not ready to
 * appear in normal user navigation yet. Routes, repositories, and deep-link
 * plumbing can remain compiled while menus/actions stay hidden.
 */

/**
 * Watch Together entry points in the item-detail overflow, on phone and TV.
 *
 * The room machinery — create/join, suggestions and voting, the WebSocket
 * transport, [org.siloserver.silo.RoomSyncEngine] — has always been compiled and
 * routable; this switch governs only whether a viewer can *reach* it.
 *
 * There is no Apple counterpart: silo-apple has no Watch Together at all, so
 * this surface has no gold-standard client to mirror and anything ambiguous
 * about it is a product decision rather than a parity bug.
 */
const val CLIENT_WATCH_TOGETHER_SURFACE_ENABLED: Boolean = true
