package com.evernym.verity.actor.resourceusagethrottling.helper

import java.time.{ZoneId, ZonedDateTime}

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId

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

}
