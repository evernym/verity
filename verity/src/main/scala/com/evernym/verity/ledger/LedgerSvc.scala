package com.evernym.verity.ledger

import java.time.ZonedDateTime

import akka.actor.ActorSystem
import com.evernym.verity.Status._
import com.evernym.verity.actor.DidPair
import com.evernym.verity.protocol.engine.{DID, WalletAccess}
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.vault.WalletAccessParam

import scala.collection.immutable.ListMap
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
  def wap: Option[WalletAccessParam]
}
object Submitter {
  def apply(did: DID, wap: Option[WalletAccessParam]): Submitter = WriteSubmitter(did, wap)
  def apply(): Submitter = ReadSubmitter()
}
case class WriteSubmitter(did: DID, wap: Option[WalletAccessParam]) extends Submitter
case class ReadSubmitter() extends Submitter {
  override def did: DID = null
  override def wap: Option[WalletAccessParam] = None
}


case class LedgerRequest(req: String, needsSigning: Boolean=true, taa: Option[TransactionAuthorAgreement]=None){
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

  private val getTAARespCache: Map[String, GetTAACachedResp] = Map.empty
  // TODO: Figure out why getNymRespCache and getAttribRespCache are useful. They never mutate from Map.empty.
  private val getNymRespCache: Map[String, GetNymCachedResp] = Map.empty
  private val getAttribRespCache: Map[String, Map[String, GetAttribCacheResp]] = Map.empty
  private val getSchemaRespCache: Map[String, GetSchemaCacheResp] = Map.empty
  private val getCredDefRespCache: Map[String, GetCredDefCacheResp] = Map.empty

  //get taa
  private def _getTAA(submitterDetail: Submitter, version: Option[String]=None): Future[Either[StatusDetail, GetTAAResp]] = {
    _getTAAFromCache(version).map { gtaar =>
      Future.successful(Right(gtaar))
    }.getOrElse(_getTAAFromLedger(submitterDetail))
  }

  private def _getTAAFromCache(version: Option[String]): Option[GetTAAResp] = {
    version match {
      case Some(v) =>
        getTAARespCache.get(v).find(_.isNotExpired).map(_.resp)
      case None =>
        // The ledger returns the latest version defined on the ledger. We will do the same in the cache.
        if (getTAARespCache.keys.isEmpty) None
        else {
          val key: String = ListMap(getTAARespCache.toSeq.sortWith(_._1 > _._1):_*).keys.head
          val latestCachedTAA: Option[GetTAACachedResp] = getTAARespCache.get(key)
          latestCachedTAA match {
            case Some(taa) => Option(taa.resp)
            case None => None
          }
        }
    }
  }

  private def _getTAAFromLedger(submitterDetail: Submitter):
  Future[Either[StatusDetail, GetTAAResp]] = {
    try {
      ledgerTxnExecutor.getTAA(submitterDetail)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get taa from the ledger: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get taa from the ledger:" + e.getMessage)))
    }
  }

  //get nym
  private def _getNym(submitterDetail: Submitter, id: String): Future[Either[StatusDetail, GetNymResp]] = {
    _getNymFromCache(id).map { gnr =>
      Future.successful(Right(gnr))
    }.getOrElse(_getNymFromLedger(submitterDetail, id))
  }

  private def _getNymFromCache(id: String): Option[GetNymResp] = {
    getNymRespCache.get(id).find(_.isNotExpired).map(_.resp)
  }

  private def _getNymFromLedger(submitterDetail: Submitter, id: String):
  Future[Either[StatusDetail, GetNymResp]] = {
    try {
      ledgerTxnExecutor.getNym(submitterDetail, id)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get nym for id $id: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get nym for id $id:" + e.getMessage)))
    }
  }

  private def _getSchema(schemaId: String): Future[Either[StatusDetail, GetSchemaResp]] = {
    _getSchemaFromCache(schemaId).map { hit =>
      Future.successful(Right(hit))
    }.getOrElse(_getSchemaFromLedger(Submitter(), schemaId))
  }

  private def _getSchemaFromCache(schemaId: String): Option[GetSchemaResp] = {
    getSchemaRespCache.get(schemaId).find(_.isNotExpired).map(_.resp)
  }

  private def _getSchemaFromLedger(submitterDetail: Submitter, schemaId: String):
  Future[Either[StatusDetail, GetSchemaResp]] = {
    try {
      ledgerTxnExecutor.getSchema(submitterDetail, schemaId)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get schema for $schemaId: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get schema for $schemaId:" + e.getMessage)))
    }
  }

  private def _getCredDef(credDefId: String): Future[Either[StatusDetail, GetCredDefResp]] = {
    _getCredDefFromCache(credDefId).map { hit =>
      Future.successful(Right(hit))
    }.getOrElse(_getCredDefFromLedger(Submitter(), credDefId))
  }

  private def _getCredDefFromCache(credDefId: String): Option[GetCredDefResp] = {
    getCredDefRespCache.get(credDefId).find(_.isNotExpired).map(_.resp)
  }

  private def _getCredDefFromLedger(submitterDetail: Submitter, credDefId: String):
  Future[Either[StatusDetail, GetCredDefResp]] = {
    try {
      ledgerTxnExecutor.getCredDef(submitterDetail, credDefId)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get credDef for $credDefId: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get credDef for $credDefId:" + e.getMessage)))
    }
  }

  //get attrib
  private def _getAttrib(submitterDetail: Submitter, id: String,
                         attribName: String): Future[Either[StatusDetail, GetAttribResp]] = {
    _getAttribFromCache(id, attribName).map { gar =>
      Future.successful(Right(gar))
    }.getOrElse(_getAttribFromLedger(submitterDetail, id, attribName))
  }

  private def _getAttribFromCache(id: String, attribName: String): Option[GetAttribResp] = {
    getAttribRespCache.get(id).flatMap(am => am.get(attribName).map(_.resp))
  }

  private def _getAttribFromLedger(submitterDetail: Submitter, id: String, attribName: String):
  Future[Either[StatusDetail, GetAttribResp]] = {
    try {
      ledgerTxnExecutor.getAttrib(submitterDetail, id, attribName)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get attribute with id $id and name $attribName: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to get attribute with id $id and name $attribName: " + e.getMessage)))
    }
  }

  //get TAA
  final def getTAA(submitterDetail: Submitter, version: Option[String]=None): Future[Either[StatusDetail, GetTAAResp]] = {
    _getTAA(submitterDetail, version)
  }

  final def getSchema(schemaId: String): Future[Either[StatusDetail, GetSchemaResp]] = {
    _getSchema(schemaId)
  }

  final def writeSchema(submitterDID: DID,
                        schemaJson: String,
                        walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = {
    try {
      ledgerTxnExecutor.writeSchema(submitterDID, schemaJson, walletAccess)
    } catch {
      case e: LedgerSvcException =>
        Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to add schema with DID $submitterDID: " + e.getMessage)))
      case e: Exception =>
        Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to add schema with DID $submitterDID: " + e.getMessage)))
    }
  }

  final def writeCredDef(submitterDID: DID,
                         credDefJson: String,
                         walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = {
    try {
      ledgerTxnExecutor.writeCredDef(submitterDID, credDefJson, walletAccess)
    } catch {
      case e: LedgerSvcException =>
        Future.successful(Left(UNHANDLED.withMessage(
          s"error while trying to add credential definition with DID $submitterDID: " + e.getMessage)))
      case e: Exception =>
        Future.successful(Left(UNHANDLED.withMessage(
          s"error while trying to add credential definition with DID $submitterDID: " + e.getMessage)))
    }
  }

  final def getCreDef(credDefId: String): Future[Either[StatusDetail, GetCredDefResp]] = {
    _getCredDef(credDefId)
  }

  final def getNym(submitterDetail: Submitter, id: String): Future[Either[StatusDetail, GetNymResp]] = {
    _getNym(submitterDetail, id)
  }

  final def addNym(submitterDetail: Submitter, targetDid: DidPair): Future[Either[StatusDetail, TxnResp]] = {
    try {
      ledgerTxnExecutor.addNym(submitterDetail, targetDid)
    } catch {
      case e: LedgerSvcException => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to add nym with DID ${targetDid.DID}: " + e.getMessage)))
      case e: Exception => Future.successful(Left(UNHANDLED.withMessage(
        s"error while trying to add nym with DID ${targetDid.DID}: " + e.getMessage)))
    }
  }

  final def getAttrib(submitterDetail: Submitter, id: String, attrName: String):
  Future[Either[StatusDetail, GetAttribResp]] = {
    _getAttrib(submitterDetail, id, attrName)
  }

  final def addAttrib(submitterDetail: Submitter, id: String, attrName: String, attrValue: String):
  Future[Either[StatusDetail, TxnResp]] = {
    ledgerTxnExecutor.addAttrib(submitterDetail, id, attrName, attrValue)
  }

  def getNymDataFut(submitterDetail: Submitter, destId: String, key: String):
  Future[Either[StatusDetail, String]] = {
    getNym(submitterDetail, destId).map {
      case Right(gnr) =>
        gnr.txnResp.data.map { data =>
          val nymData = data
          val keyValueOpt = nymData.get(key).flatMap{
            case str: String => Some(str)
            case _ => None
          }
          if (keyValueOpt.isDefined) Right(keyValueOpt.orNull)
          else Left(DATA_NOT_FOUND.withMessage(s"no $key found for the identifier: $destId"))
        }.getOrElse(Left(DATA_NOT_FOUND.withMessage(s"no data found for the identifier: $destId")))
      case Left(err: StatusDetail) => Left(err)
    }
  }

  def getAttribFut(submitterDetail: Submitter, destId: String, attribName: String):
  Future[AttribResult] = {
    getAttrib(submitterDetail, destId, attribName).map {
      case Right(gar) =>
        gar.txnResp.data.map { data =>
          val attribData = data
          val attribValueOpt = attribData
            .get(attribName)
          if (attribValueOpt.isDefined)
            AttribResult(attribName, value = attribValueOpt)
          else
            AttribResult(attribName, error = Option(DATA_NOT_FOUND.withMessage(
              s"attribute '$attribName' not found for identifier: $destId")))
        }.getOrElse(AttribResult(attribName, error = Option(DATA_NOT_FOUND.withMessage(
          s"attribute '$attribName' not found for identifier: $destId"))))
      case Left(err: StatusDetail) =>
        AttribResult(attribName, error = Option(err))
    }
  }

}

class BaseLedgerSvcException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class LedgerSvcException(override val message: String) extends BaseLedgerSvcException
