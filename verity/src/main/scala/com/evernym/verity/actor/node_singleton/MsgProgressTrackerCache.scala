package com.evernym.verity.actor.node_singleton

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}

class MsgProgressTrackerCacheImpl extends Extension{

  //TODO: how to make sure this extension is thread safe?

  private var trackingParams: Set[TrackingParam] = Set.empty

  def startProgressTracking(trackingParam: TrackingParam): Unit = {
    trackingParams = trackingParams + trackingParam
  }

  def stopProgressTracking(trackingId: String): Unit =
    trackingParams = trackingParams.filterNot(tp => tp.trackingId == trackingId)

  def isTracked(id: String): Boolean = trackingParams.exists(_.trackingId == id)

  def allIdsBeingTracked: TrackingStatus = TrackingStatus(trackingParams)
}

object MsgProgressTrackerCache extends ExtensionId[MsgProgressTrackerCacheImpl] with ExtensionIdProvider {
  val GLOBAL_TRACKING_ID = "global"

  override def lookup = MsgProgressTrackerCache

  override def createExtension(system: ExtendedActorSystem) = new MsgProgressTrackerCacheImpl
}

case class TrackingParam(trackingId: String)
case class TrackingStatus(trackedIds: Set[TrackingParam])