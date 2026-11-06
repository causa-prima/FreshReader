package io.github.causaprima.freshreader.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

suspend fun Core.awaitSyncStatus(predicate: (SyncStatus) -> Boolean): SyncStatus =
    withTimeout(5.seconds) { syncStatus.first(predicate) }

/** Waits until all read marks have reached the Server. */
suspend fun Core.awaitSynced() = awaitSyncStatus { it == SyncStatus.Synced }
