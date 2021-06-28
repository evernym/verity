package com.evernym.verity.actor.node_singleton

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.UsageWarningStatusChunk
import com.evernym.verity.actor.resourceusagethrottling.warning.ResourceWarningStatusMngrCommon
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime

class ResourceWarningStatusMngrCacheImpl extends ResourceWarningStatusMngrCommon with Extension {

  def initWarningList(cuws: UsageWarningStatusChunk): Unit = {
    if (cuws.currentChunkNumber == 1) {
      entityWarningStatus = cuws.usageWarningStatus
    } else {
      entityWarningStatus = entityWarningStatus ++ cuws.usageWarningStatus
    }
  }

  def isInUnwarningPeriod(userToken: String, resourceName: String): Boolean = {
    val curDateTime = getCurrentUTCZonedDateTime
    entityWarningStatus.find(_._1 == userToken).exists { case (_, uwd) =>
      uwd.resourcesStatus.find(_._1 == resourceName).exists { case (_, urwd) =>
        urwd.isInUnwarningPeriod(curDateTime)
      }
    }
  }

}

object ResourceWarningStatusMngrCache extends ExtensionId[ResourceWarningStatusMngrCacheImpl] with ExtensionIdProvider {

  override def lookup = ResourceWarningStatusMngrCache

  override def createExtension(system: ExtendedActorSystem) = new ResourceWarningStatusMngrCacheImpl
}