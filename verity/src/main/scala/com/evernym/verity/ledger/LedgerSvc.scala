package com.evernym.verity.ledger

import java.time.ZonedDateTime
import akka.actor.ActorSystem
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.vault.WalletAPIParam

import scala.concurrent.{ExecutionContextExecutor, Future}

case class TxnResp(from: DID,
                   dest: Option[DID],
                   data: Option[Map[String, Any]],
                   txnType: String,
                   txnTime: Option[Long],
                   reqId: Long,
                   seqNo: Option[Long])

case class LedgerTAA(version: String, text: String)
case class GetTAAResp(txnResp: TxnResp, taa: LedgerTAA)

case class GetNymResp(txnResp: TxnResp, nym: Option[String], verkey: Option[String], role: Option[String] = None)
case class GetSchemaResp(txnResp: TxnResp, schema: Option[SchemaV1])
case class GetCredDefResp(txnResp: TxnResp,
                          credDef: Option[CredDefV1])
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
  def did: DID
  def wap: Option[WalletAPIParam]
  def wapReq: WalletAPIParam = wap.getOrElse(throw new Exception("Signed Requests require Wallet Info"))
}
object Submitter {
  def apply(did: DID, wap: Option[WalletAPIParam]): Submitter = WriteSubmitter(did, wap)
  def apply(): Submitter = ReadSubmitter()
}
case class WriteSubmitter(did: DID, wap: Option[WalletAPIParam]) extends Submitter
case class ReadSubmitter() extends Submitter {
  override def did: DID = null
  override def wap: Option[WalletAPIParam] = None
}


case class LedgerRequest(req: String, needsSigning: Boolean=true, taa: Option[TransactionAuthorAgreement]=None) extends ActorMessage {
  def prepared(newRequest: String): LedgerRequest = this.copy(req=newRequest)
}

case class TransactionAuthorAgreement(version: String, digest: String, mechanism: String, timeOfAcceptance: String)


trait CachedResp {
  def time: ZonedDateTime

  def isNotExpired (implicit expiresInSeconds: Int): Boolean = {
    val curTime = getCurrentUTCZonedDateTime
    val expiryTime = time.plusSeconds(expiresInSeconds)
    expiryTime.isAfter(curTime)
  }

}

case class GetTAACachedResp(time: ZonedDateTime, resp: GetTAAResp) extends CachedResp
case class GetNymCachedResp(time: ZonedDateTime, resp: GetNymResp) extends CachedResp
case class GetAttribCacheResp(time: ZonedDateTime, resp: GetAttribResp) extends CachedResp
case class GetSchemaCacheResp(time: ZonedDateTime, resp: GetSchemaResp) extends CachedResp
case class GetCredDefCacheResp(time: ZonedDateTime, resp: GetCredDefResp) extends CachedResp


trait LedgerSvc {

  val DEST = "dest"
  val VER_KEY = "verkey"
  val URL = "url"

  def system: ActorSystem

  def ledgerTxnExecutor: LedgerTxnExecutor

  implicit val expiresInSeconds: Int = 600

  implicit val executor: ExecutionContextExecutor = system.dispatcher

  //get taa
  private def _getTAA(submitterDetail: Submitter, version: Option[String]=None): Future[GetTAAResp] = {
    _getTAAFromLedger(submitterDetail)
  }

  private def _getTAAFromLedger(submitterDetail: Submitter): Future[GetTAAResp] = {
    ledgerTxnExecutor.getTAA(submitterDetail) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get taa from the ledger: ${e.getMessage}"))
    }
  }

  //get nym
  private def _getNym(submitterDetail: Submitter, id: String): Future[GetNymResp] = {
    _getNymFromLedger(submitterDetail, id)
  }

  private def _getNymFromLedger(submitterDetail: Submitter, id: String): Future[GetNymResp] = {
    ledgerTxnExecutor.getNym(submitterDetail, id) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get nym for id $id: " + e.getMessage))
    }
  }

  private def _getSchema(schemaId: String): Future[GetSchemaResp] = {
    _getSchemaFromLedger(Submitter(), schemaId)
  }

  private def _getSchemaFromLedger(submitterDetail: Submitter, schemaId: String): Future[GetSchemaResp] = {
    ledgerTxnExecutor.getSchema(submitterDetail, schemaId) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get schema for $schemaId: " + e.getMessage))
    }
  }

  private def _getCredDef(credDefId: String): Future[GetCredDefResp] = {
    _getCredDefFromLedger(Submitter(), credDefId)
  }

  private def _getCredDefFromLedger(submitterDetail: Submitter, credDefId: String): Future[GetCredDefResp] = {
    ledgerTxnExecutor.getCredDef(submitterDetail, credDefId) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get credDef for $credDefId: " + e.getMessage))
    }
  }

  //get attrib
  private def _getAttrib(submitterDetail: Submitter, id: String,
                         attribName: String): Future[GetAttribResp] = {
    _getAttribFromLedger(submitterDetail, id, attribName)
  }

  private def _getAttribFromLedger(submitterDetail: Submitter, id: String, attribName: String):
  Future[GetAttribResp] = {
    ledgerTxnExecutor.getAttrib(submitterDetail, id, attribName).recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to get attribute with id $id and name $attribName: " + e.getMessage))
    }
  }

  //get TAA
  final def getTAA(submitterDetail: Submitter, version: Option[String]=None): Future[GetTAAResp] = {
    _getTAA(submitterDetail, version)
  }

  final def getSchema(schemaId: String): Future[GetSchemaResp] = {
    _getSchema(schemaId)
  }

  final def writeSchema(submitterDID: DID,
                        schemaJson: String,
                        walletAccess: WalletAccess): Future[TxnResp] = {
    ledgerTxnExecutor.writeSchema(submitterDID, schemaJson, walletAccess) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to add schema with DID $submitterDID: " + e.getMessage))
    }
  }

  final def prepareSchemaForEndorsement(submitterDID: DID,
                                        schemaJson: String,
                                        endorserDID: DID,
                                        walletAccess: WalletAccess): Future[LedgerRequest] = {
    ledgerTxnExecutor.prepareSchemaForEndorsement(submitterDID, schemaJson, endorserDID, walletAccess)
  }

  final def writeCredDef(submitterDID: DID,
                         credDefJson: String,
                         walletAccess: WalletAccess): Future[TxnResp] = {
    ledgerTxnExecutor.writeCredDef(submitterDID, credDefJson, walletAccess) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to add credential definition with DID $submitterDID: " + e.getMessage))
    }
  }

  final def prepareCredDefForEndorsement(submitterDID: DID,
                                         credDefJson: String,
                                         endorserDID: DID,
                                         walletAccess: WalletAccess): Future[LedgerRequest] = {
    try {
      ledgerTxnExecutor.prepareCredDefForEndorsement(submitterDID, credDefJson, endorserDID, walletAccess)
    } catch {
      case e: Throwable =>
        Future.failed(e)
    }
  }

  final def getCreDef(credDefId: String): Future[GetCredDefResp] = {
    _getCredDef(credDefId)
  }

  final def getNym(submitterDetail: Submitter, id: String): Future[GetNymResp] = {
    _getNym(submitterDetail, id)
  }

  final def addNym(submitterDetail: Submitter, targetDid: DidPair): Future[TxnResp] = {
    ledgerTxnExecutor.addNym(submitterDetail, targetDid) recover {
      case e: LedgerSvcException => throw StatusDetailException(UNHANDLED.withMessage(
        s"error while trying to add nym with DID ${targetDid.DID}: " + e.getMessage))
    }
  }

  final def getAttrib(submitterDetail: Submitter, id: String, attrName: String): Future[GetAttribResp] = {
    _getAttrib(submitterDetail, id, attrName)
  }

  final def addAttrib(submitterDetail: Submitter, id: String, attrName: String, attrValue: String): Future[TxnResp] = {
    ledgerTxnExecutor.addAttrib(submitterDetail, id, attrName, attrValue)
  }

  def getNymDataFut(submitterDetail: Submitter, destId: String, key: String): Future[String] = {
    getNym(submitterDetail, destId).map {
      _.txnResp.data match {
        case Some(nymData) =>
          nymData.get(key) match {
            case Some(str: String) => str
            case _ => throw StatusDetailException(DATA_NOT_FOUND.withMessage(s"no $key found for the identifier: $destId"))
          }
        case None => throw StatusDetailException(DATA_NOT_FOUND.withMessage(s"no data found for the identifier: $destId"))
      }
    }
  }

  def getAttribFut(submitterDetail: Submitter, destId: String, attribName: String): Future[AttribResult] = {
    getAttrib(submitterDetail, destId, attribName).map {
      _.txnResp.data.map { attribData: Map[String, Any] =>
        attribData.get(attribName) match {
          case Some(value) => AttribResult(attribName, value = Some(value))
          case None => AttribResult(attribName, error = Option(DATA_NOT_FOUND.withMessage(
            s"attribute '$attribName' not found for identifier: $destId")))
        }
      }.getOrElse(
        AttribResult(attribName, error = Option(DATA_NOT_FOUND.withMessage(
        s"attribute '$attribName' not found for identifier: $destId")))
      )
    }.recover {
      case e: StatusDetailException => AttribResult(attribName, error = Option(e.statusDetail))
    }
  }

}

class BaseLedgerSvcException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class LedgerSvcException(override val message: String) extends BaseLedgerSvcException
