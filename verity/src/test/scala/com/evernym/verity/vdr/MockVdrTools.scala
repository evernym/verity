package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.ledger.{GetAttribResp, Submitter}
import com.evernym.verity.vdr.base.InMemLedger
import com.evernym.verity.vdr.service._

import scala.concurrent.{ExecutionContext, Future}

//Mocked vdrtools (dependent on in-memory ledgers) to be used in unit/integration tests
class MockVdrTools(ledgerRegistry: MockLedgerRegistry)(implicit ec: ExecutionContext)
  extends VdrTools {

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  var idToLedgers: Map[Namespace, InMemLedger]= Map.empty

  override def ping(namespaces: List[Namespace]): Future[Map[Namespace, VdrResults.PingResult]] = {
    val allNamespaces = if (namespaces.isEmpty) ledgerRegistry.ledgers.keys else namespaces
    Future.successful(allNamespaces.map(n => n -> new VdrResults.PingResult("SUCCESS", "successful")).toMap)
  }

  override def prepareDid(txnSpecificParams: TxnSpecificParams,
                          submitterDid: DidStr,
                          endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
      val id = json.get(VDRAdapterUtil.ID).asText
      addLedgerMapping(id, ledger)
      ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                             submitterDid: DidStr,
                             endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
      val id = json.get(VDRAdapterUtil.ID).asText
      addLedgerMapping(id, ledger)
      ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                              submitterDid: DidStr,
                              endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams);
      val id = json.get(VDRAdapterUtil.ID).asText()
      addLedgerMapping(id, ledger)
      ledger.prepareCredDefTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def submitTxn(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String,
                         signature: Array[Byte],
                         endorsement: String): Future[TxnResult] = {
    ledgerRegistry.withLedger(namespace) { ledger: InMemLedger =>
      ledger.submitTxn(txnBytes)
    }
  }

  override def resolveSchema(schemaId: FqSchemaId): Future[VdrSchema] = {
    Future(withLedger(schemaId).resolveSchema(schemaId))
  }

  override def resolveSchema(schemaId: FqSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    resolveSchema(schemaId)
  }

  override def resolveCredDef(credDefId: FqCredDefId): Future[VdrCredDef] = {
    Future(withLedger(credDefId).resolveCredDef(credDefId))
  }

  override def resolveCredDef(credDefId: FqCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    resolveCredDef(credDefId)
  }

  override def resolveDid(fqDid: FqDID): Future[VdrDid] = {
    Future(withLedger(fqDid).resolveDid(fqDid))
  }

  override def resolveDid(fqDid: FqDID,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    resolveDid(fqDid)
  }

  private def addLedgerMapping(id: String, ledger: InMemLedger): Unit = {
    idToLedgers = idToLedgers + (id -> ledger)
  }

  private def withLedger(id: String): InMemLedger = {
    idToLedgers.getOrElse(id, ledgerRegistry.getLedger(id))
  }

  def submitRawTxn(namespace: Namespace,
                   txnBytes: Array[Byte]): Future[TxnResult] = ???

  def submitQuery(namespace: Namespace,
                  query: String): Future[TxnResult] = ???


  //============================================= WORKAROUND =======================================================
  //NOTE: this is workaround until vdr tools apis starts supporting updating did docs

  def addNym(submitter: Submitter, didPair: DidPair): Future[Unit] = {
    Future(withLedger(didPair.did).addNym(submitter, didPair))
  }

  def addAttrib(submitter: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Future[Unit] = {
    Future(withLedger(did).addAttrib(submitter, did, attrName, attrValue))
  }

  def getAttrib(submitter: Submitter, did: DidStr, attrName: AttrName): Future[GetAttribResp] = {
    Future(withLedger(did).getAttrib(submitter, did, attrName))
  }
}
