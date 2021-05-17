package com.evernym.verity.actor.testkit.actor

import akka.actor.ActorSystem
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{DATA_NOT_FOUND, StatusDetail}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.{DID, VerKey}
import org.json.JSONObject

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.collection.JavaConverters._
import scala.util.{Left, Random}

//TODO: This is not perfect/exact mock ledger object
//it doesn't have any privilege checking etc.
//it is more like data store only

class MockLedgerSvc(val system: ActorSystem) extends LedgerSvc {
  override val ledgerTxnExecutor: LedgerTxnExecutor = new MockLedgerTxnExecutor()
}

object MockLedgerTxnExecutor {
  def buildTxnResp(from: DID,
                   dest: Option[DID],
                   data: Option[Map[String, Any]],
                   txnType: String,
                   txnTime: Option[Long]=None,
                   reqId: Option[Long]=None,
                   seqNo: Option[Long]=None): TxnResp = {
    TxnResp(from, dest, data, txnType, txnTime, reqId.getOrElse(1), seqNo)
  }
}

class MockLedgerTxnExecutor() extends LedgerTxnExecutor {

  case class NymDetail(verKey: VerKey)

  var taa: Option[LedgerTAA] = None
  var nyms: Map[DID, NymDetail] = Map.empty
  var schemas: Map[SchemaId, GetSchemaResp] = Map.empty
  var credDefs: Map[CredDefId, GetCredDefResp] = Map.empty
  var attribs: Map[DID, Map[AttrName, AttrValue]] = Map.empty

  type SchemaId = String
  type CredDefId = String
  type DID = String
  type AttrName = String
  type AttrValue = String

  override def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = {
    throw new NotImplementedError("not yet implemented")
  }

  override def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp = {
    throw new NotImplementedError("not yet implemented")
  }

  override def getTAA(submitter: Submitter): Future[Either[StatusDetail, GetTAAResp]] = {
    Future(
      taa match {
        case Some(t) => Right(
          GetTAAResp(
            MockLedgerTxnExecutor.buildTxnResp(
              submitter.did,
              Some(submitter.did),
              Some(Map("text"->"taa", "version"->"1.0")),
              "6"),
            t
          )
        )
        case None => Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    )
  }

  def getNym(submitter: Submitter, id: String): Future[Either[StatusDetail, GetNymResp]] = {
    Future(
      nyms.get(id).map { nd =>
        Right(
          GetNymResp(
            MockLedgerTxnExecutor.buildTxnResp(
              id,
              Some(id),
              Some(Map("dest" -> id, "verkey" -> nd.verKey)),
              "105"
            ),
            Some(id),
            Some(nd.verKey)
          )
        )
      }.getOrElse {
        Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    )
  }

  def writeSchema(submitterDID: DID,
                  schemaJson: String,
                  walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = {
    val jSONObject = new JSONObject(schemaJson)
    val id = jSONObject.getString("id")
    val name = jSONObject.getString("name")
    val version = jSONObject.getString("version")
    val ver = jSONObject.getString("ver")
    val attrNames = jSONObject.getJSONArray("attrNames").asScala.map(_.toString).toSeq
    val seqNo = Random.nextInt(1000)
    val txnResp = MockLedgerTxnExecutor.buildTxnResp(submitterDID, None, None, "107", seqNo = Option(seqNo))
    val schemaResp = GetSchemaResp(
      txnResp,
      Some(SchemaV1(
        id,
        name,
        version,
        attrNames,
        Some(seqNo),
        ver
      ))
    )
    schemas += id -> schemaResp
    Future.successful(Right(txnResp))
  }

  def prepareSchemaForEndorsement(submitterDID: DID,
                                  schemaJson: String,
                                  endorserDID: DID,
                                  walletAccess: WalletAccess): Future[LedgerRequest] = ???

  def getSchema(submitter: Submitter, schemaId: String): Future[Either[StatusDetail, GetSchemaResp]] = {
    Future.successful {
      schemas.get(schemaId).map { schema =>
        Right(schema)
      }.getOrElse {
        Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    }
  }

  def writeCredDef(submitterDID: DID,
                   credDefJson: String,
                   walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = {
    val jSONObject = new JSONObject(credDefJson)
    val id = jSONObject.getString("id")
    val schemaId = jSONObject.getString("schemaId")
    val ver = jSONObject.getString("ver")
    val typ = jSONObject.getString("type")
    val tag = jSONObject.getString("tag")
    val value = jSONObject.getJSONObject("value").toMap.asScala.toMap

    val seqNo = Random.nextInt(1000)
    val txnResp = MockLedgerTxnExecutor.buildTxnResp(submitterDID, None, None, "108", seqNo = Option(seqNo))
    val getCredDefResp = GetCredDefResp(
        txnResp,
        Some(CredDefV1(
          id,
          typ,
          schemaId,
          tag,
          ver,
          value
        ))
      )
    credDefs += id -> getCredDefResp
    Future.successful(Right(txnResp))
  }

  def prepareCredDefForEndorsement(submitterDID: DID,
                                   credDefJson: String,
                                   endorserDID: DID,
                                   walletAccess: WalletAccess): Future[LedgerRequest] = ???

  def getCredDef(submitter: Submitter, credDefId: String): Future[Either[StatusDetail, GetCredDefResp]] = {
    Future(
      credDefs.get(credDefId).map { d =>
        Right(d)
      }.getOrElse {
        Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    )
  }

  def addNym(submitter: Submitter, targetDid: DidPair): Future[Either[StatusDetail, TxnResp]] = {
    nyms += targetDid.DID -> NymDetail(targetDid.verKey)
    Future(
      Right(MockLedgerTxnExecutor.buildTxnResp(targetDid.DID, Some(targetDid.DID), None, "1"))
    )
  }

  def getAttrib(submitter: Submitter, did: DID, attrName: String): Future[Either[StatusDetail, GetAttribResp]] = {
    Future(
      attribs.get(did).map { didAttribs =>
        didAttribs.get(attrName).map { attribValue =>
          Right(
            GetAttribResp(
              MockLedgerTxnExecutor.buildTxnResp(
                did,
                Some(did),
                Some(Map(attrName -> attribValue)),
                "104"
              )
            )
          )
        }.getOrElse {
          Left(DATA_NOT_FOUND) //TODO: Replace with correct error
        }
      }.getOrElse {
        Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    )
  }

  def addAttrib(submitter: Submitter, did: DID, attrName: String, attrValue: String):
  Future[Either[StatusDetail, TxnResp]] = {
    val oldDIDAttribs = attribs.getOrElse(did, Map.empty)
    val newDIDAttribs = oldDIDAttribs ++ Map(attrName -> attrValue)
    attribs += did -> newDIDAttribs
    Future(
      Right(MockLedgerTxnExecutor.buildTxnResp(did, Some(did), None, "100"))
    )
  }

  override def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Either[StatusDetail, Map[String, Any]]] = ???

}
