package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.base.InMemLedger
import com.evernym.verity.vdr.service._

import scala.concurrent.{ExecutionContext, Future}

//in-memory version of VDRTools to be used in tests unit/integration tests
class MockVdrTools(ledgerRegistry: MockLedgerRegistry)(implicit ec: ExecutionContext)
  extends VdrTools {

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  override def ping(namespaces: List[Namespace]): Future[Map[String, VdrResults.PingResult]] = {
    val allNamespaces = if (namespaces.isEmpty) ledgerRegistry.ledgers.flatMap(_.namespaces) else namespaces
    Future.successful(allNamespaces.map(n => n -> new VdrResults.PingResult("SUCCESS", "successful")).toMap)
  }

  override def prepareDid(txnSpecificParams: TxnSpecificParams,
                          submitterDid: DidStr,
                          endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
      val id = json.get("id").asText
      ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                             submitterDid: DidStr,
                             endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
      val id = json.get("id").asText
      ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                              submitterDid: DidStr,
                              endorser: Option[String]): Future[PreparedTxnResult] = {
    ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams);
      val id = json.get("id").asText()
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

  def submitRawTxn(namespace: Namespace,
                   txnBytes: Array[Byte]): Future[TxnResult] = ???

  def submitQuery(namespace: Namespace,
                  query: String): Future[TxnResult] = ???

  override def resolveSchema(schemaId: FQSchemaId): Future[VdrSchema] = {
    ledgerRegistry.forLedger(schemaId) { ledger: InMemLedger =>
      ledger.resolveSchema(schemaId)
    }
  }

  override def resolveSchema(schemaId: FQSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    ledgerRegistry.forLedger(schemaId) { ledger: InMemLedger =>
      ledger.resolveSchema(schemaId)
    }
  }

  override def resolveCredDef(credDefId: FQCredDefId): Future[VdrCredDef] = {
    ledgerRegistry.forLedger(credDefId) { ledger: InMemLedger =>
      ledger.resolveCredDef(credDefId)
    }
  }

  override def resolveCredDef(credDefId: FQCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    ledgerRegistry.forLedger(credDefId) { ledger: InMemLedger =>
      ledger.resolveCredDef(credDefId)
    }
  }

  override def resolveDid(fqDid: FQDid): Future[VdrDid] = {
    ledgerRegistry.forLedger(fqDid) { ledger: InMemLedger =>
      ledger.resolveDid(fqDid)
    }
  }

  override def resolveDid(fqDid: FQDid,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    ledgerRegistry.forLedger(fqDid) { ledger: InMemLedger =>
      ledger.resolveDid(fqDid)
    }
  }
}

//TODO: this is mainly to confirm the mock vdr implementation throws appropriate errors
// if given identifiers are not correct FQIds
object FQIdentifier {

  val validSchemes = Set(SCHEME_NAME_INDY_SCHEMA, SCHEME_NAME_INDY_CRED_DEF)

  def apply(fqId: String, validNamespaces: List[Namespace]):  FQIdentifier = {
    val fqIdentifier = {
      if (fqId.startsWith("did:sov")) FQIdentifier("sov", fqId.replace("did:sov:", ""))
      else if (fqId.startsWith("did:indy:sovrin")) FQIdentifier("indy:sovrin", fqId.replace("did:indy:sovrin:", ""))
      else if (fqId.startsWith("schema:sov")) FQIdentifier("sov", fqId.replace("schema:sov:", ""))
      else if (fqId.startsWith("creddef:sov")) FQIdentifier("sov", fqId.replace("creddef:sov:", ""))
      else throw new RuntimeException("invalid identifier: " + fqId)
    }
    if (! validNamespaces.contains(fqIdentifier.vdrNamespace )) {
      throw new RuntimeException("namespace not supported: " + fqIdentifier.vdrNamespace)
    }
    fqIdentifier
  }

}

case class FQIdentifier(vdrNamespace: String, methodIdentifier: String)


class InvalidIdentifierException(msg: String) extends RuntimeException(msg)