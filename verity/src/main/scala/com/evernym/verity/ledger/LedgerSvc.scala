package com.evernym.verity.ledger

import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.vault.WalletAPIParam

import scala.concurrent.{ExecutionContext, Future}

case class TxnResp(from: DidStr,
                   dest: Option[DidStr],
                   data: Option[Map[String, Any]],
                   txnType: String,
                   txnTime: Option[Long],
                   reqId: Long,
                   seqNo: Option[Long])

case class LedgerTAA(version: String, text: String)

case class GetTAAResp(txnResp: TxnResp, taa: LedgerTAA)

case class SchemaV1(id: String,
                    name: String,
                    version: String,
                    attrNames: Seq[String],
                    seqNo: Option[Int],
                    ver: String)

case class CredDefV1(id: String,
                     `type`: String,
                     schemaId: String,
                     tag: String,
                     ver: String,
                     value: Map[String, Any])

trait Submitter {
  def did: DidStr
  def wap: Option[WalletAPIParam]
  def wapReq: WalletAPIParam = wap.getOrElse(throw new Exception("Signed Requests require Wallet Info"))
}
object Submitter {
  def apply(did: DidStr, wap: Option[WalletAPIParam]): Submitter = WriteSubmitter(did, wap)
  def apply(): Submitter = ReadSubmitter()
}
case class WriteSubmitter(did: DidStr, wap: Option[WalletAPIParam]) extends Submitter
case class ReadSubmitter() extends Submitter {
  override def did: DidStr = null
  override def wap: Option[WalletAPIParam] = None
}


case class LedgerRequest(req: String, needsSigning: Boolean=true, taa: Option[TransactionAuthorAgreement]=None) extends ActorMessage {
  def prepared(newRequest: String): LedgerRequest = this.copy(req=newRequest)
}

case class TransactionAuthorAgreement(version: String, digest: String, mechanism: String, timeOfAcceptance: String)



trait LedgerSvc {

  def ledgerTxnExecutor: LedgerTxnExecutor

  implicit def executionContext: ExecutionContext

  final def addNym(submitterDetail: Submitter, targetDid: DidPair): Future[Unit] = {
    ledgerTxnExecutor
      .addNym(submitterDetail, targetDid)
      .recover {
        case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
          s"error while trying to add nym with DID ${targetDid.did}: " + e.getMessage))
      }
  }

  final def addAttrib(submitterDetail: Submitter, id: String, attrName: String, attrValue: String): Future[Unit] = {
    ledgerTxnExecutor
      .addAttrib(submitterDetail, id, attrName, attrValue)
  }
}

class BaseLedgerSvcException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class LedgerSvcException(override val message: String) extends BaseLedgerSvcException
