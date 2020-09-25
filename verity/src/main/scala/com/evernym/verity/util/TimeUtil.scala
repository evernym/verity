package com.evernym.verity.util

import java.time.zone.ZoneRulesException
import java.time.{DateTimeException, Instant, ZoneId, ZonedDateTime}
import java.util.Calendar

import com.evernym.verity.constants.Constants.UTC
import com.evernym.verity.Exceptions.InvalidValueException
import jdk.internal.dynalink.linker.ConversionComparator.Comparison
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

object TimeUtil {

  type IsoDateTime = String

  def now: Long = DateTime.now.withZone(DateTimeZone.UTC).getMillis

  def nowDateString: IsoDateTime = longToDateString(now)

  def longToDateString(value: Long): IsoDateTime = new DateTime(value).withZone(DateTimeZone.UTC).toString

  def isExpired(e: Option[IsoDateTime]): Boolean = !isNotExpired(e)

  def isNotExpired(e: Option[IsoDateTime]): Boolean = {
    e.exists { expiration =>
      val expireTime = DateTime.parse(expiration).withZone(DateTimeZone.UTC).getMillis
      !(now > expireTime)
    }
  }

  def dateStrToMillis(date: IsoDateTime): Long = DateTime.parse(date).withZone(DateTimeZone.UTC).getMillis

  def toMonth(time: IsoDateTime): Int = TimeZoneUtil
    .getUTCZonedDateTimeFromMillis(dateStrToMillis(time))
    .getMonthValue

  def isDateExpired(date: IsoDateTime, expirationDate: IsoDateTime): Boolean =
    dateStrToMillis(date) >= dateStrToMillis(expirationDate)

  def dateAfterDuration(startDate: IsoDateTime, period: Duration): IsoDateTime =
    longToDateString(dateStrToMillis(startDate) + period.toMillis)

  def isWithinRange(e: IsoDateTime, window: Duration): Boolean = isWithinRange(e, window, window)

  def isWithinRange(e: IsoDateTime, lower: Duration, upper: Duration): Boolean = {
    def withinWindow(timestamp: Long, lowerLimit: Long, upperLimit: Long): Boolean = {
      val withinLower = lowerLimit < timestamp
      val withinUpper = timestamp < upperLimit
      withinLower && withinUpper
    }

    val expireTime = dateStrToMillis(e)
    val currentTime = now
    withinWindow(expireTime, currentTime - lower.toMillis, currentTime + upper.toMillis)
  }

}

object TimeZoneUtil {
  val MAX_NANO_SECONDS = 999999999
  val MIN_NANO_SECONDS = 0

  def getZoneId(id: String): ZoneId = {
    try {
      val shortIds = ZoneId.SHORT_IDS.asScala
      val actualId = shortIds.getOrElse(id, id)
      ZoneId.of(actualId)
    } catch {
      case _ @ (_:ZoneRulesException | _:DateTimeException) =>
        throw new InvalidValueException(Option(s"invalid zone id: '$id'"))
    }
  }

  val UTCZoneId: ZoneId = getZoneId(UTC)

  def getCurrentZonedDateTime(implicit zoneId: ZoneId): ZonedDateTime = ZonedDateTime.now(zoneId)

  def getZonedDateTime(year: Int, month: Int, day: Int, hour: Int, min: Int, seconds: Int, nanos: Int)
                      (implicit zoneId: ZoneId): ZonedDateTime = {
    try {
      ZonedDateTime.of(year, month, day, hour, min, seconds, nanos, zoneId)
    } catch {
      case _: DateTimeException => throw new InvalidValueException(
        Option(s"Invalid date time: $year-$month-$day $hour:$min:$seconds"))
    }
  }

  def getZonedDateTimeWithMinNanos(year: Int, month: Int, day: Int, hour: Int, min: Int, seconds: Int)
                                  (implicit zoneId: ZoneId): ZonedDateTime = {
    getZonedDateTime(year, month, day, hour, min, seconds, MIN_NANO_SECONDS)
  }

  def getZonedDateTimeWithMaxNanos(year: Int, month: Int, day: Int, hour: Int, min: Int, seconds: Int)
                                  (implicit zoneId: ZoneId): ZonedDateTime = {
    getZonedDateTime(year, month, day, hour, min, seconds, MAX_NANO_SECONDS)
  }

  def getMillisFromZonedDateTime(ldt: ZonedDateTime): Long = ldt.toInstant.toEpochMilli

  def getZonedDateTimeFromMillis(millis: Long)(implicit zoneId: ZoneId): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId)

  def getUTCZonedDateTimeFromMillis(millis: Long): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), UTCZoneId)

  def getMillisForCurrentUTCZonedDateTime: Long = getMillisFromZonedDateTime(getCurrentUTCZonedDateTime)

  def getCurrentUTCZonedDateTime: ZonedDateTime = {
    val zdt: ZonedDateTime = ZonedDateTime.now()
    zdt.withZoneSameInstant(UTCZoneId)
  }
}
