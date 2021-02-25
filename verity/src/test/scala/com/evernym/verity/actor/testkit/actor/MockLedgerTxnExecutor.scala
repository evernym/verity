package com.evernym.verity.actor.testkit.actor

import akka.actor.ActorSystem
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{DATA_NOT_FOUND, StatusDetail}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess
import com.evernym.verity.protocol.engine.{DID, VerKey}

import scala.concurrent.Future
import scala.util.Left

//TODO: This is not perfect/exact mock ledger object
//it doesn't have any privilege checking etc.
//it is more like data store only

class MockLedgerSvc(val system: ActorSystem) extends LedgerSvc {
  override val ledgerTxnExecutor: LedgerTxnExecutor = MockLedgerTxnExecutor
}


object MockLedgerTxnExecutor extends LedgerTxnExecutor {

  case class NymDetail(verKey: VerKey)

  var taa: Option[LedgerTAA] = None
  var nyms: Map[String, NymDetail] = Map.empty
  var schemas: Map[String, GetSchemaResp] = Map.empty
  var credDefs: Map[String, GetCredDefResp] = Map.empty
  var attribs: Map[String, Map[String, String]] = Map.empty

  override def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = {
    throw new NotImplementedError("not yet implemented")
  }

  override def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp = {
    throw new NotImplementedError("not yet implemented")
  }

  def buildTxnResp(from: DID,
                   dest: Option[DID],
                   data: Option[Map[String, Any]],
                   txnType: String,
                   txnTime: Option[Long]=None,
                   reqId: Option[Long]=None,
                   seqNo: Option[Long]=None): TxnResp = {
    TxnResp(from, dest, data, txnType, txnTime, reqId.getOrElse(1), seqNo)
  }

  override def getTAA(submitter: Submitter): Future[Either[StatusDetail, GetTAAResp]] = {
    Future(
      taa match {
        case Some(t) => Right(
          GetTAAResp(
            buildTxnResp(
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
            buildTxnResp(
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
                  walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = ???

  def prepareSchemaForEndorsement(submitterDID: DID,
                                  schemaJson: String,
                                  endorserDID: DID,
                                  walletAccess: WalletAccess): Future[LedgerRequest] = ???

  def getSchema(submitter: Submitter, schemaId: String): Future[Either[StatusDetail, GetSchemaResp]] = {
    Future(
      schemas.get(schemaId).map { schema =>
        Right(schema)
      }.getOrElse {
        Left(DATA_NOT_FOUND) //TODO: Replace with correct error
      }
    )
  }

  def writeCredDef(submitterDID: DID,
                   credDefJson: String,
                   walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = ???

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
      Right(buildTxnResp(targetDid.DID, Some(targetDid.DID), None, "1"))
    )
  }

  def getAttrib(submitter: Submitter, did: DID, attrName: String): Future[Either[StatusDetail, GetAttribResp]] = {
    Future(
      attribs.get(did).map { didAttribs =>
        didAttribs.get(attrName).map { attribValue =>
          Right(
            GetAttribResp(
              buildTxnResp(
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
      Right(buildTxnResp(did, Some(did), None, "100"))
    )
  }

  override def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Either[StatusDetail, Map[String, Any]]] = ???

}
