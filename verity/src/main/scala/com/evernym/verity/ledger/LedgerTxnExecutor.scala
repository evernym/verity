package com.evernym.verity.ledger

import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.did.{DidPair, DidStr}

import scala.concurrent.Future

case class LedgerExecutorException(message: String,
                                   cause: Throwable = None.orNull) extends Exception(message, cause)

trait LedgerTxnExecutor {

  //NOTE: this `addNym` is only used by tests, may be we should replace it with vdr api calls
  def addNym(submitter: Submitter, targetDid: DidPair): Future[Unit]

  def addAttrib(submitter: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Future[Unit]

  def getAttrib(submitter: Submitter, did: DidStr, attrName: AttrName): Future[GetAttribResp]

  def getTAA(submitter: Submitter): Future[GetTAAResp]

  def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Map[String, Any]]

  protected def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp

  protected def buildTxnRespForWriteOp(resp: Map[String, Any]): Unit
}
