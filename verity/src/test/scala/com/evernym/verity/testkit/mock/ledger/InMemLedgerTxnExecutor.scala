package com.evernym.verity.testkit.mock.ledger

import com.evernym.verity.Status.{LEDGER_POOL_NO_RESPONSE, StatusDetail}
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess

import scala.collection.mutable.{Map => MMap}
import scala.concurrent.{ExecutionContextExecutor, Future}

class MockInMemLedgerTxnExecutor(initData: InitLedgerData)
                                (implicit val executor: ExecutionContextExecutor)
                                extends LedgerTxnExecutor {

  private val nymMap: MMap[String, DidPair] = MMap(initData.nymInitMap.toSeq: _*)
  private val attrMap: MMap[String, String] = MMap(initData.attrInitMap.toSeq: _*)

  def mockTxnResp(subDid: DID, did: DID, data: Option[Map[String, Any]], txnTypeNum: String): TxnResp = {
    TxnResp(subDid, Some(did), data, txnTypeNum, Some(0), 0, Some(0))
  }

  def attrKey(did: DID, attrName: String): String = s"$did-$attrName"

  override def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Either[StatusDetail, Map[String, Any]]] = ???

  override def getTAA(submitter: Submitter): Future[Either[StatusDetail, GetTAAResp]] = ???

  override def addNym(submitter: Submitter, targetDid: DidPair): Future[Either[StatusDetail, TxnResp]] = ???

  override def getNym(submitter: Submitter, id: String): Future[Either[StatusDetail, GetNymResp]] = ???

  override def writeSchema(submitterDID: DID,
                           schemaJson:  String,
                           walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = ???

  override def writeCredDef(submitterDID: DID,
                            credDefJson: String,
                            walletAccess: WalletAccess): Future[Either[StatusDetail, TxnResp]] = ???

  override def getSchema(submitter: Submitter, schemaId: String): Future[Either[StatusDetail, GetSchemaResp]] = ???


  override def getCredDef(submitter: Submitter, credDefId: String): Future[Either[StatusDetail, GetCredDefResp]] = ???

  override def addAttrib(submitterDetail: Submitter, did: DID, attrName: String, attrValue: String): Future[Either[StatusDetail, TxnResp]] = {
    attrMap.put(attrKey(did, attrName), attrValue)

    Future {Right(mockTxnResp(submitterDetail.did, did, None, "100"))}
  }

  override def getAttrib(submitterDetail: Submitter, did: DID, attrName: String): Future[Either[StatusDetail, GetAttribResp]] = {
    val value = attrMap.get(attrKey(did, attrName))
      .map{x =>
        val dataMap =  Map(attrName -> x)
        val txnResp = mockTxnResp(submitterDetail.did, did, Some(dataMap), "104")
        Right(GetAttribResp(txnResp))
      }
      .getOrElse(
        Left(LEDGER_POOL_NO_RESPONSE.withMessage("Attrib not found"))
      )

    Future {
      value
    }
  }

  override def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp = {
    ???
  }

  override def buildTxnRespForWriteOp(resp: Map[String, Any]): TxnResp = {
    ???
  }
}
