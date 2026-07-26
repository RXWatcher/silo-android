package org.siloserver.silo.watchtogether

import kotlin.math.abs

/**
 * Pure state-report cadence/suppression decision, shared across mobile and TV.
 *
 * A `state_report` goes out once per [cadenceMs], EXCEPT inside a +/-
 * [suppressWindowMs] window around a pending transport command's local execute
 * time — reporting our position right as we are about to seek/play would feed
 * the server a stale pre-execute sample and fight the sync barrier.
 *
 * This lived in `androidApp` and was hand-copied into the TV controller, with a
 * comment asking the two to be kept in lockstep. They sit next to
 * [roomTransportAuthorized] now for the same reason it is shared: a room is one
 * protocol, and two platforms disagreeing about when to report is a
 * desynchronisation bug that only shows up with two devices in a room.
 *
 * @param nowMs current local (monotonic) clock in ms.
 * @param lastReportMs local time the last state_report was emitted.
 * @param cadenceMs minimum spacing between reports.
 * @param pendingExecuteAtMs local (monotonic) execute time of the next command, or null.
 * @param suppressWindowMs half-width of the +/- suppression window around a pending execute.
 */
fun shouldEmitRoomStateReport(
    nowMs: Long,
    lastReportMs: Long,
    cadenceMs: Long,
    pendingExecuteAtMs: Long?,
    suppressWindowMs: Long,
): Boolean {
    if (nowMs - lastReportMs < cadenceMs) return false
    if (pendingExecuteAtMs != null && abs(nowMs - pendingExecuteAtMs) <= suppressWindowMs) return false
    return true
}
