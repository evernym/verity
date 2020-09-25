package com.evernym.verity.actor.node_singleton

object MsgProgressTrackerCache {

  private var trackingIds: Set[String] = Set.empty

  def startProgressTracking(trackingId: String): Unit =
    trackingIds = trackingIds + trackingId

  def stopProgressTracking(trackingId: String): Unit =
    trackingIds = trackingIds.filterNot(_ == trackingId)

  def isTracked(id: String): Boolean = trackingIds.contains(id)

  def allIdsBeingTracked: TrackingIds = TrackingIds(trackingIds)
}

case class TrackingIds(ids: Set[String])