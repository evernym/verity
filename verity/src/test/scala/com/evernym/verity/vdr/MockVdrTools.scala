package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.{DidPair, DidStr}
import com.evernym.verity.ledger.Submitter
import com.evernym.verity.vdr.base.InMemLedger
import com.evernym.verity.vdr.service._

import scala.concurrent.{ExecutionContext, Future}

//in-memory version of VDRTools to be used in tests unit/integration tests
class MockVdrTools(ledgerRegistry: MockLedgerRegistry)(implicit ec: ExecutionContext)
  extends VdrTools {

  var idToLedgers: Map[String, InMemLedger]= Map.empty

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  override def ping(namespaces: List[Namespace]): Future[Map[String, VdrResults.PingResult]] = {
    val allNamespaces = if (namespaces.isEmpty) ledgerRegistry.ledgers.flatMap(_.allSupportedNamespaces) else namespaces
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
    idToLedgers.get(schemaId) match {
      case Some(ledger) => Future(ledger.resolveSchema(schemaId))
      case None         => Future(ledgerRegistry.getLedger(schemaId).resolveSchema(schemaId))
    }
  }

  override def resolveSchema(schemaId: FqSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    resolveSchema(schemaId)
  }

  override def resolveCredDef(credDefId: FqCredDefId): Future[VdrCredDef] = {
    idToLedgers.get(credDefId) match {
      case Some(ledger) => Future(ledger.resolveCredDef(credDefId))
      case None         => Future(ledgerRegistry.getLedger(credDefId).resolveCredDef(credDefId))
    }
  }

  override def resolveCredDef(credDefId: FqCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    resolveCredDef(credDefId)
  }

  override def resolveDid(fqDid: FqDID): Future[VdrDid] = {
    ledgerRegistry.forLedger(fqDid) { ledger: InMemLedger =>
      ledger.resolveDid(fqDid)
    }
  }

  override def resolveDid(fqDid: FqDID,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    resolveDid(fqDid)
  }

  private def addLedgerMapping(id: String, ledger: InMemLedger): Unit = {
    idToLedgers = idToLedgers + (id -> ledger)
  }

  def submitRawTxn(namespace: Namespace,
                   txnBytes: Array[Byte]): Future[TxnResult] = ???

  def submitQuery(namespace: Namespace,
                  query: String): Future[TxnResult] = ???


  //============================================= WORKAROUND =======================================================
  //NOTE: this is workaround until vdr tools apis starts supporting updating did docs

  def addNym(submitter: Submitter, didPair: DidPair): Future[Unit] = {
    idToLedgers.get(didPair.did) match {
      case Some(ledger) => Future(ledger.addNym(submitter, didPair))
      case None         => Future(ledgerRegistry.getLedger(didPair.did).addNym(submitter, didPair))
    }
  }

  def addAttrib(submitter: Submitter, did: DidStr, attrName: String, attrValue: String): Future[Unit] = {
    idToLedgers.get(did) match {
      case Some(ledger) => Future(ledger.addAttrib(submitter, did, attrName, attrValue))
      case None         => Future(ledgerRegistry.getLedger(did).addAttrib(submitter, did, attrName, attrValue))
    }
  }
}
