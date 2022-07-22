package com.evernym.verity.ledger

import com.evernym.verity.did.{DidStr, DidPair}

import scala.concurrent.Future

case class LedgerExecutorException(message: String,
                                   cause: Throwable = None.orNull) extends Exception(message, cause)

trait LedgerTxnExecutor {

  def addNym(submitter: Submitter, targetDid: DidPair): Future[Unit]

  def addAttrib(submitter: Submitter, did: DidStr, attrName: String, attrValue: String): Future[Unit]

  def getTAA(submitter: Submitter): Future[GetTAAResp]

  def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Map[String, Any]]

  protected def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp

  protected def buildTxnRespForWriteOp(resp: Map[String, Any]): Unit
}
