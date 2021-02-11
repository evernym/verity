package com.evernym.verity.actor.node_singleton

object MsgProgressTrackerCache {

  val GLOBAL_TRACKING_ID = "global"

  private var trackingIds: Set[TrackingParam] = Set.empty

  def startProgressTracking(trackingParam: TrackingParam): Unit = {
    trackingIds = trackingIds + trackingParam
  }

  def stopProgressTracking(trackingId: String): Unit =
    trackingIds = trackingIds.filterNot(tp => tp.trackingId == trackingId || tp.ipAddress.contains(trackingId))

  def isTracked(id: String): Boolean = trackingIds.exists(_.trackingId == id)

  def allIdsBeingTracked: TrackingStatus = TrackingStatus(trackingIds)
}

case class TrackingParam(trackingId: String, ipAddress: Option[String]=None)
case class TrackingStatus(trackedIds: Set[TrackingParam])