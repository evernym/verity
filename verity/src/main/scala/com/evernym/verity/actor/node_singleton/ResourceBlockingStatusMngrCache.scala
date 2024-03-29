package com.evernym.verity.actor.node_singleton

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.USAGE_BLOCKED
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.UsageBlockingStatusChunk
import com.evernym.verity.actor.resourceusagethrottling.blocking.ResourceBlockingStatusMngrCommon
import com.evernym.verity.actor.resourceusagethrottling.{EntityId, ResourceName}
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime

/**
 * This cache is node singleton and gets updated when any changes happens to the
 * main blocking list (which would be on a cluster singleton)
 */
class ResourceBlockingStatusMngrCacheImpl extends ResourceBlockingStatusMngrCommon with Extension {

  //TODO: how to make sure this extension is thread safe?

  def initBlockingList(cubs: UsageBlockingStatusChunk): Unit = {
    if (cubs.currentChunkNumber == 1) {
      entityBlockingStatus = cubs.usageBlockingStatus
    } else {
      entityBlockingStatus = entityBlockingStatus ++ cubs.usageBlockingStatus
    }
  }

  def checkIfUsageBlocked(entityId: EntityId, resourceName: ResourceName): Unit = {
    val curDateTime = getCurrentUTCZonedDateTime
    val filteredEntityId = entityBlockingStatus.filter(_._1 == entityId)
    val filteredBlockedResources = filterBlockedUserResources(filteredEntityId, Option(curDateTime)).values.headOption
    val isBlocked = filteredBlockedResources.exists { ubs =>
      val isEntityBlocked = ubs.status.isBlocked(curDateTime)
      val isResourceBlocked = ubs.resourcesStatus.get(resourceName).exists(_.isBlocked(curDateTime))
      isEntityBlocked || isResourceBlocked
    }
    if (isBlocked) {
      throw new BadRequestErrorException(USAGE_BLOCKED.statusCode, Option("usage blocked"))
    }
  }

  def isUnblocked(entityId: EntityId, resourceName: ResourceName): Boolean = {
    val curDateTime = getCurrentUTCZonedDateTime

    entityBlockingStatus.find(_._1 == entityId).exists { case (_, ubs) =>
      ubs.status.isUnblocked(curDateTime) ||
        ubs.resourcesStatus.find(_._1 == resourceName).exists { case (_, urbd) =>
          urbd.isUnblocked(curDateTime)
        }
    }
  }
}

object ResourceBlockingStatusMngrCache extends ExtensionId[ResourceBlockingStatusMngrCacheImpl] with ExtensionIdProvider {

  override def lookup = ResourceBlockingStatusMngrCache

  override def createExtension(system: ExtendedActorSystem) = new ResourceBlockingStatusMngrCacheImpl
}