package com.evernym.verity.actor.resourceusagethrottling.helper

import java.time.{ZoneId, ZonedDateTime}
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.actor.resourceusagethrottling.{COUNTERPARTY_ID_PREFIX, OWNER_ID_PREFIX, RESOURCE_NAME_ALL, RESOURCE_NAME_ENDPOINT_ALL, RESOURCE_NAME_MESSAGE_ALL, RESOURCE_TYPE_ENDPOINT, RESOURCE_TYPE_MESSAGE, RESOURCE_TYPE_NAME_ENDPOINT, RESOURCE_TYPE_NAME_MESSAGE, ResourceName, ResourceType, ResourceTypeName}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_CREATE_MSG
import com.evernym.verity.agentmsg.msgpacker.MsgFamilyDetail
import com.evernym.verity.protocol.engine.{MsgName, MsgType}
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.evernym.verity.util.Util.{isDID, isVerKey}

import scala.util.matching.Regex

object ResourceUsageUtil {

  implicit val zoneId: ZoneId = UTCZoneId

  def getTimePeriodInSeconds(period: Option[Long]): Long = {
    period match {
      case None => -1 //indefinite time (either for block or unblock)
      case Some(p: Long) if p == -1 => p
      case Some(p: Long) if p >= 0  => p
      case Some(p: Long) => throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(s"unsupported period: $p"))
    }
  }

  def getZonedDateTimeFromPeriod(startDateTime: ZonedDateTime, periodInSeconds: Long): Option[ZonedDateTime] = {
    if (periodInSeconds == -1) None
    else Option(startDateTime.plusSeconds(periodInSeconds))
  }

  val USER_ID_OWNER_REGEX: Regex = s"($OWNER_ID_PREFIX)(.+)".r
  val USER_ID_COUNTERPARTY_REGEX: Regex = s"($COUNTERPARTY_ID_PREFIX)(.+)".r
  val USER_ID_REGEX: Regex = s"($OWNER_ID_PREFIX|$COUNTERPARTY_ID_PREFIX)(.+)".r

  def isUserId(value: String): Boolean = {
    value match {
      case USER_ID_REGEX(_, rawId) => isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

  def isUserIdOwner(value: String): Boolean = {
    value match {
      case USER_ID_OWNER_REGEX(_, rawId) => isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

  def isUserIdCounterparty(value: String): Boolean = {
    value match {
      case USER_ID_COUNTERPARTY_REGEX(_, rawId) => isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

  def isUserIdOrPattern(value: String): Boolean = {
    value match {
      case USER_ID_REGEX(_, rawId) => rawId == "*" || isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

  def getCreateMsgReqMsgName(msgName: MsgName): String = s"${MSG_TYPE_CREATE_MSG}_$msgName"

  val MSG_FAMILY_VERSION_REGEX: Regex = raw"\d+(\.\d+)*".r

  def getMessageResourceName(msgType: MsgType): ResourceName = {
    msgType.familyName match {
      case MSG_FAMILY_VERSION_REGEX(_*) => msgType.msgName
      case realFamilyName => s"$realFamilyName/${msgType.msgName}"
    }
  }

  def getMessageResourceName(msgFamilyDetail: MsgFamilyDetail): ResourceName =
    getMessageResourceName(msgFamilyDetail.msgType)

  def getCreateMessageResourceName(msgType: MsgType): ResourceName = {
    val createMsgReqMsgType = msgType.copy(msgName = getCreateMsgReqMsgName(msgType.msgName))
    getMessageResourceName(createMsgReqMsgType)
  }

  def getResourceTypeName(resourceType: ResourceType): ResourceTypeName = {
    resourceType match {
      case RESOURCE_TYPE_ENDPOINT => RESOURCE_TYPE_NAME_ENDPOINT
      case RESOURCE_TYPE_MESSAGE => RESOURCE_TYPE_NAME_MESSAGE
    }
  }

  def getResourceUniqueName(resourceTypeName: ResourceTypeName, resourceName: ResourceName): ResourceName = {
    resourceName match {
      case RESOURCE_NAME_ALL => s"$resourceTypeName.$resourceName"
      case _ => resourceName
    }
  }

  def getResourceSimpleName(resourceUniqueName: ResourceName): ResourceName = {
    resourceUniqueName match {
      case RESOURCE_NAME_ENDPOINT_ALL | RESOURCE_NAME_MESSAGE_ALL => RESOURCE_NAME_ALL
      case _ => resourceUniqueName
    }
  }

}
