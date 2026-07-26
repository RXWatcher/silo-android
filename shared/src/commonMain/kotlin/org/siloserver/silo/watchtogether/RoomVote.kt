package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion

/**
 * Which suggestion a vote room has settled on, or null when there is nothing to
 * start yet.
 *
 * Mirrors the server's rule exactly (`Service.VoteWinner`): the list arrives
 * ordered by vote count then age, so the winner is its head, ties go to
 * whoever suggested first, and a room where nobody has voted has **no** winner
 * rather than defaulting to the oldest entry. Keeping the client's idea of the
 * leader identical to the server's is what stops "Start winner" being refused
 * with a 409 the user cannot explain.
 */
fun roomVoteWinner(suggestions: List<Suggestion>): Suggestion? =
    suggestions.firstOrNull()?.takeIf { it.voteCount > 0 }

/** Whether this room decides what plays by vote rather than host choice. */
fun RoomSnapshot?.isVoteRoom(): Boolean =
    this != null && selectionMode == RoomSelectionMode.Vote
