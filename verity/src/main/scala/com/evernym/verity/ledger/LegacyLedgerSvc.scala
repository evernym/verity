package com.evernym.verity.ledger

import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.did.DidStr
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

case class GetAttribResp(txnResp: TxnResp)
case class AttribResult(name: String, value: Option[Any] = None, error: Option[StatusDetail] = None)


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


case class LedgerRequest(req: String,
                         needsSigning: Boolean=true,
                         taa: Option[TransactionAuthorAgreement]=None) extends ActorMessage {
  def prepared(newRequest: String): LedgerRequest = this.copy(req=newRequest)
}

case class TransactionAuthorAgreement(version: String, digest: String, mechanism: String, timeOfAcceptance: String)


trait LegacyLedgerSvc {

  def ledgerTxnExecutor: LedgerTxnExecutor

  implicit def executionContext: ExecutionContext

  //both `addAttrib` and `getAttrib` apis (or their equivalent) is currently not supported by VDR apis
  // once they are made available in vdr apis, we can/should replace this `LedgerSvc` api calls with
  // corresponding vdr api calls.
  final def addAttrib(submitterDetail: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Future[Unit] = {
    ledgerTxnExecutor
      .addAttrib(submitterDetail, did, attrName, attrValue)
  }

  final def getAttrib(submitterDetail: Submitter, did: DidStr, attrName: AttrName): Future[AttribResult] = {
    val result = ledgerTxnExecutor.getAttrib(submitterDetail, did, attrName).recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get attribute with id $did and name $attrName: " + e.getMessage))
    }
    result.map {
      _.txnResp.data.map { attribData: Map[String, Any] =>
        attribData.get(attrName) match {
          case Some(value) => AttribResult(attrName, value = Some(value))
          case None => AttribResult(attrName, error = Option(DATA_NOT_FOUND.withMessage(
            s"attribute '$attrName' not found for identifier: $did")))
        }
      }.getOrElse(AttribResult(attrName, error = Option(DATA_NOT_FOUND.withMessage(
        s"attribute '$attrName' not found for identifier: $did"))))
    }.recover {
      case e: StatusDetailException => AttribResult(attrName, error = Option(e.statusDetail))
    }
  }
}

class BaseLedgerSvcException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class LedgerSvcException(override val message: String) extends BaseLedgerSvcException
