package com.hamed.smartdelivery.domain

/**
 * The app exposes a single tracking state; turn-by-turn navigation is not an app mode.
 */
enum class GpsMode { STOPPED, TRACKING }

object GpsModeCycle {
    fun next(current: GpsMode): GpsMode = when (current) {
        GpsMode.STOPPED -> GpsMode.TRACKING
        GpsMode.TRACKING -> GpsMode.STOPPED
    }
}

data class ClusterExclusion(val clusterId: String, val untilEpochMillis: Long)
data class ClusterVisit(val clusterId: String, val visitSequence: Long, val otherClustersVisitedSince: Int = 0, val producedOrder: Boolean = false)
data class ClusterWaitState(val clusterId: String, val enteredAtEpochMillis: Long?, val pausedAtEpochMillis: Long? = null)

object ClusterPolicy {
    const val WAIT_MILLIS = 5 * 60 * 1000L
    const val AUTO_EXCLUSION_MILLIS = 15 * 60 * 1000L
    const val MANUAL_EXCLUSION_MILLIS = 10 * 60 * 1000L
    const val EXIT_GRACE_MILLIS = 15 * 1000L

    fun enterCluster(clusterId: String, now: Long) = ClusterWaitState(clusterId, now)

    /** A wait only expires after five full minutes of accumulated, unpaused time. */
    fun shouldAutoExclude(wait: ClusterWaitState, now: Long): Boolean {
        val start = wait.enteredAtEpochMillis ?: return false
        if (wait.pausedAtEpochMillis != null || now < start) return false
        return now - start >= WAIT_MILLIS
    }

    fun pause(wait: ClusterWaitState, now: Long): ClusterWaitState =
        if (wait.enteredAtEpochMillis == null || wait.pausedAtEpochMillis != null || now < wait.enteredAtEpochMillis) wait
        else wait.copy(pausedAtEpochMillis = now)

    /** Shift the start forward by the paused duration so paused time is never counted. */
    fun resume(wait: ClusterWaitState, now: Long): ClusterWaitState {
        val pausedAt = wait.pausedAtEpochMillis ?: return wait
        val start = wait.enteredAtEpochMillis ?: return wait.copy(pausedAtEpochMillis = null)
        if (now < pausedAt) return wait
        return wait.copy(enteredAtEpochMillis = start + (now - pausedAt), pausedAtEpochMillis = null)
    }

    fun leave(wait: ClusterWaitState): ClusterWaitState =
        wait.copy(enteredAtEpochMillis = null, pausedAtEpochMillis = null)

    /** Manual switching is always allowed while a cluster is selected, regardless of arrival or wait time. */
    fun canManualSwitch(wait: ClusterWaitState, now: Long): Boolean = true

    fun manualSwitch(clusterId: String, now: Long) = ClusterExclusion(clusterId, now + MANUAL_EXCLUSION_MILLIS)
    fun automaticTimeout(clusterId: String, now: Long) = ClusterExclusion(clusterId, now + AUTO_EXCLUSION_MILLIS)
    fun isTemporarilyExcluded(exclusion: ClusterExclusion?, now: Long) =
        exclusion != null && now < exclusion.untilEpochMillis

    /** Clears only temporary exclusions; visit history is held separately and is never mutated. */
    fun resetTemporaryExclusions(): List<ClusterExclusion> = emptyList()

    fun canRepeat(visit: ClusterVisit?, isOrderProducingCluster: Boolean): Boolean =
        visit == null || isOrderProducingCluster || visit.otherClustersVisitedSince >= 4
}
