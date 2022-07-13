package com.evernym.verity.vdrtools

import com.evernym.verity.util2.Exceptions.{InvalidValueException, MissingReqFieldException}
import com.evernym.verity.vdrtools.ledger.LedgerTxnExecutorBase.LedgerResult

package object ledger {

  def extractReqValue(resp: LedgerResult, fieldToExtract: String): Any = {
    resp.get(fieldToExtract) match {
      case Some(r) if r == null =>
        throw new InvalidValueException(Option("ledger response parsing error " +
          s"(invalid value found for field '$fieldToExtract': $r)"))
      case Some(r) => r
      case _ =>
        throw new MissingReqFieldException(Option(s"ledger response parsing error ('$fieldToExtract' key is missing)"))
    }
  }

  def extractOptValue(resp: LedgerResult, fieldToExtract: String, nullAllowed: Boolean = false): Option[Any] = {
    try {
      Option(extractReqValue(resp, fieldToExtract))
    } catch {
      case _:InvalidValueException if nullAllowed => None
      case _:MissingReqFieldException => None
    }
  }
}
