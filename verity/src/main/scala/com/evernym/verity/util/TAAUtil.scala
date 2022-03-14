package com.evernym.verity.util

import com.evernym.verity.util2.Exceptions.InvalidValueException
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.util.Try

object TAAUtil {
  val taaAcceptanceDatePattern = "yyyy-MM-dd"
  val taaAcceptanceFormat = DateTimeFormat.forPattern(taaAcceptanceDatePattern)
  def taaAcceptanceDateParse(str: String): Option[DateTime] = {
    Try(DateTime.parse(str, taaAcceptanceFormat)).toOption
  }
  def taaAcceptanceEpochDateTime(str: String): Long = {
    val acceptanceDate: Option[DateTime] = taaAcceptanceDateParse(str)
    acceptanceDate match {
      case Some(a) =>
        // Remove time from the DateTime to avoid the InvalidClientTaaAcceptanceError "Txn Author Agreement acceptance
        // time <epoch time> is too precise and is a privacy risk" error message from libindy.
        (a.getMillis - (a.getMillis % (1000*60*60*24))) / 1000
      case None => throw new InvalidValueException(
        Option(s"Invalid TAA Acceptance Date: $str. Date must be in $taaAcceptanceDatePattern format."))
    }
  }
}
