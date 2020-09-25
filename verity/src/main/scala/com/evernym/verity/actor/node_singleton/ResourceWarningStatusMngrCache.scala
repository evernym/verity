package com.evernym.verity.actor.node_singleton

import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.UsageWarningStatusChunk
import com.evernym.verity.actor.resourceusagethrottling.warning.ResourceWarningStatusMngrCommon
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime

object ResourceWarningStatusMngrCache extends ResourceWarningStatusMngrCommon {

  def initWarningList(cuws: UsageWarningStatusChunk): Unit = {
    if (cuws.currentChunkNumber == 1) {
      resourceWarningStatus = cuws.usageWarningStatus
    } else {
      resourceWarningStatus = resourceWarningStatus ++ cuws.usageWarningStatus
    }
  }

  def isInUnwarningPeriod(userToken: String, resourceName: String): Boolean = {
    val curDateTime = getCurrentUTCZonedDateTime
    resourceWarningStatus.find(_._1 == userToken).exists { case (_, uwd) =>
      uwd.resourcesStatus.find(_._1 == resourceName).exists { case (_, urwd) =>
        urwd.isInUnwarningPeriod(curDateTime)
      }
    }
  }

}