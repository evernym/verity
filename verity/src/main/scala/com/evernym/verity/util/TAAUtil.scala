package com.evernym.verity.util

import com.evernym.verity.util2.Exceptions.InvalidValueException
import org.joda.time.{DateTimeZone, LocalDateTime}
import org.joda.time.format.DateTimeFormat

import scala.util.Try

object TAAUtil {
  val taaAcceptanceDatePattern = "yyyy-MM-dd"
  val taaAcceptanceFormat = DateTimeFormat.forPattern(taaAcceptanceDatePattern)
  def taaAcceptanceDateParse(str: String): Option[LocalDateTime] = {
    Try(LocalDateTime.parse(str, taaAcceptanceFormat)).toOption
  }
  def taaAcceptanceEpochDateTime(str: String): Long = {
    val acceptanceDate: Option[LocalDateTime] = taaAcceptanceDateParse(str)
    acceptanceDate match {
      case Some(a) =>
        val sec = a.toDateTime(DateTimeZone.UTC).getMillis / 1000
        // Remove time from the DateTime to avoid the InvalidClientTaaAcceptanceError "Txn Author Agreement acceptance
        // time <epoch time> is too precise and is a privacy risk" error message from libindy.
        sec - (sec % (60*60*24))
      case None => throw new InvalidValueException(
        Option(s"Invalid TAA Acceptance Date: $str. Date must be in $taaAcceptanceDatePattern format."))
    }
  }
}
