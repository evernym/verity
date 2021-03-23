package com.evernym.verity.actor.node_singleton

object MsgProgressTrackerCache {

  val GLOBAL_TRACKING_ID = "global"

  private var trackingParams: Set[TrackingParam] = Set.empty

  def startProgressTracking(trackingParam: TrackingParam): Unit = {
    trackingParams = trackingParams + trackingParam
  }

  def stopProgressTracking(trackingId: String): Unit =
    trackingParams = trackingParams.filterNot(tp => tp.trackingId == trackingId)

  def isTracked(id: String): Boolean = trackingParams.exists(_.trackingId == id)

  def allIdsBeingTracked: TrackingStatus = TrackingStatus(trackingParams)
}

case class TrackingParam(trackingId: String)
case class TrackingStatus(trackedIds: Set[TrackingParam])