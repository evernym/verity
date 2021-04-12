package com.evernym.verity.actor.resourceusagethrottling.helper

import java.time.{ZoneId, ZonedDateTime}

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.constants.Constants.{COUNTERPARTY_ID_PREFIX, OWNER_ID_PREFIX}
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

  val OWNER_ID_PATTERN: String = OWNER_ID_PREFIX + "*"
  val COUNTERPARTY_ID_PATTERN: String = COUNTERPARTY_ID_PREFIX + "*"

  val USER_ID_REGEX: Regex = s"($OWNER_ID_PREFIX|$COUNTERPARTY_ID_PREFIX)(.+)".r

  def isUserIdForResourceUsageTracking(value: String): Boolean = {
    value match {
      case USER_ID_REGEX(_, rawId) => isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

  def isUserIdOrPatternForResourceUsageTracking(value: String): Boolean = {
    value match {
      case USER_ID_REGEX(_, rawId) => rawId == "*" || isDID(rawId) || isVerKey(rawId)
      case _ => false
    }
  }

}
