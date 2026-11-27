package com.hamed.smartdelivery.domain

/** GPS cycle: stopped -> tracking (car marker) -> navigation (arrow) -> stopped -> tracking. */
enum class GpsMode { STOPPED, TRACKING, NAVIGATION }

object GpsModeCycle {
    fun next(current: GpsMode): GpsMode = when (current) {
        GpsMode.STOPPED -> GpsMode.TRACKING
        GpsMode.TRACKING -> GpsMode.NAVIGATION
        GpsMode.NAVIGATION -> GpsMode.STOPPED
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
    fun shouldAutoExclude(wait: ClusterWaitState, now: Long): Boolean {
        val start = wait.enteredAtEpochMillis ?: return false
        if (wait.pausedAtEpochMillis != null) return false
        return now - start >= WAIT_MILLIS
    }
    fun manualSwitch(clusterId: String, now: Long) = ClusterExclusion(clusterId, now + MANUAL_EXCLUSION_MILLIS)
    fun automaticTimeout(clusterId: String, now: Long) = ClusterExclusion(clusterId, now + AUTO_EXCLUSION_MILLIS)
    fun isTemporarilyExcluded(exclusion: ClusterExclusion?, now: Long) =
        exclusion != null && now < exclusion.untilEpochMillis

    /** Clears only temporary exclusions; visit history is held separately and is never mutated. */
    fun resetTemporaryExclusions(): List<ClusterExclusion> = emptyList()

    fun canRepeat(visit: ClusterVisit?, isOrderProducingCluster: Boolean): Boolean =
        visit == null || isOrderProducingCluster || visit.otherClustersVisitedSince >= 4
}
