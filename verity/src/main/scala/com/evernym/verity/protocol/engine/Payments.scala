package com.evernym.verity.protocol.engine

import scala.util.Try

trait Payments {

  def createTxnFeesReq(submitterDID: Option[DID], paymentMethod: String): Try[String]

  def parseTxnFeesResponse(paymentMethod: String, response: String): Try[String]
}

