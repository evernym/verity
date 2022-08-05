package com.evernym.verity.actor.testkit.actor

import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.{VDR_LEDGER_PREFIX_MAPPINGS, VDR_UNQUALIFIED_LEDGER_PREFIX}
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, StatusDetailException}
import com.evernym.verity.ledger._
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.protocol.engine.MockVDRAdapter
import com.evernym.verity.vdr.VDRAdapter

import scala.concurrent.{ExecutionContext, Future}

//TODO: This is not perfect/exact mock ledger object
//it doesn't have any privilege checking etc.
//it is more like data store only

class MockLegacyLedgerSvc(appConfig: AppConfig,
                          vdrAdapter: VDRAdapter)(implicit val executionContext: ExecutionContext)
  extends LegacyLedgerSvc {
  override val ledgerTxnExecutor: LedgerTxnExecutor = new MockLedgerTxnExecutor( executionContext, appConfig, vdrAdapter)
}

object MockLedgerTxnExecutor {
  def buildTxnResp(from: DidStr,
                   dest: Option[DidStr],
                   data: Option[Map[String, Any]],
                   txnType: String,
                   txnTime: Option[Long]=None,
                   reqId: Option[Long]=None,
                   seqNo: Option[Long]=None): TxnResp = {
    TxnResp(from, dest, data, txnType, txnTime, reqId.getOrElse(1), seqNo)
  }
}

class MockLedgerTxnExecutor(ec: ExecutionContext,
                            appConfig: AppConfig,
                            vdrAdapter: VDRAdapter)
  extends LedgerTxnExecutor {

  lazy implicit val executionContext: ExecutionContext = ec

  var taa: Option[LedgerTAA] = None
  lazy val vdrUnqualifiedLedgerPrefix: String = appConfig.getStringReq(VDR_UNQUALIFIED_LEDGER_PREFIX)
  lazy val vdrLedgerPrefixMappings: Map[String, String] = appConfig.getMap(VDR_LEDGER_PREFIX_MAPPINGS)

  override def getTAA(submitter: Submitter): Future[GetTAAResp] = {
    Future(
      taa match {
        case Some(t) =>
          GetTAAResp(
            MockLedgerTxnExecutor.buildTxnResp(
              submitter.did,
              Some(submitter.did),
              Some(Map("text"-> "taa", "version"-> "1.0")),
              "6"),
            t
          )
        case None => throw StatusDetailException(DATA_NOT_FOUND)
      }
    )
  }

  def addNym(submitter: Submitter, didPair: DidPair): Future[Unit] = {
    mockVdrAdapter.addNym(submitter, didPair)
  }

  def addAttrib(submitter: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Future[Unit] = {
    mockVdrAdapter.addAttrib(submitter, did, attrName, attrValue)
  }

  override def getAttrib(submitter: Submitter, did: DidStr, attrName: AttrName): Future[GetAttribResp] = {
    mockVdrAdapter.getAttrib(submitter, did, attrName)
  }

  private lazy val mockVdrAdapter = vdrAdapter.asInstanceOf[MockVDRAdapter]

  override def completeRequest(submitter: Submitter, req: LedgerRequest): Future[Map[String, Any]] =
    throw new NotImplementedError("not yet implemented")

  override def buildTxnRespForReadOp(resp: Map[String, Any]): TxnResp =
    throw new NotImplementedError("not yet implemented")

  override def buildTxnRespForWriteOp(resp: Map[String, Any]): Unit =
    throw new NotImplementedError("not yet implemented")
}
