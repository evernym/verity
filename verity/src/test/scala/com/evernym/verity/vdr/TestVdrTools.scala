package com.evernym.verity.vdr

import com.evernym.vdrtools.vdr.VdrParams.{CacheOptions, TaaConfig}
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.vdr.service._

import scala.concurrent.{ExecutionContext, Future}

//in-memory version of VDRTools to be used in tests (unit/integration)
class TestVdrTools(ledgerRegistry: TestLedgerRegistry)(implicit ec: ExecutionContext)
  extends VdrTools {

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  override def ping(namespaces: List[Namespace]): Future[Map[String, VdrResults.PingResult]] = {
    val allNamespaces = if (namespaces.isEmpty) ledgerRegistry.ledgers.flatMap(_.namespaces) else namespaces
    Future.successful(allNamespaces.map(n => n -> new VdrResults.PingResult("0", "SUCCESS")).toMap)
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
      val id = json.get("id").asText
      ledger.prepareCredDefTxn(txnSpecificParams, id, submitterDid, endorser)
    }
  }

  override def prepareDid(txnSpecificParams: TxnSpecificParams,
                          submitterDid: DidStr,
                          endorser: Option[String]): Future[PreparedTxnResult] = ???

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

//TODO: refactor this to some common utility etc
object TestFQIdentifier {
  def apply(fqId: String, validNamespaces: List[Namespace]): TestFQIdentifier = {
    if (fqId.startsWith("did:")) {
      val didFqId = fqId.replace("did:", "")
      validNamespaces.find(n => didFqId.startsWith(s"$n:")) match {
        case Some(namespace) =>
          val identifier = didFqId.replace(s"$namespace:", "")
          TestFQIdentifier("did", namespace, identifier)
        case None => throw new InvalidIdentifierException("invalid identifier: " + fqId)
      }
    } else {
      throw new InvalidIdentifierException("invalid identifier: " + fqId)
    }
  }
}

case class TestFQIdentifier(scheme: String, namespace: String, identifier: String)

case class TestLedgerRegistry(var ledgers: List[InMemLedger] = List.empty) {
  def addLedger(ledger: InMemLedger): Unit = synchronized {
    ledgers :+= ledger
  }

  def cleanup() : Unit = {
    ledgers = List.empty
  }

  //--helper functions

  def addDidDoc(dd: TestVDRDidDoc): Future[Unit] = {
    forLedger(dd.id) { ledger: InMemLedger =>
      ledger.addDidDoc(dd)
    }
  }

  def forLedger[T](fqDidStr: DidStr)(f: InMemLedger => T): Future[T] = {
    try {
      val testIdentifier = TestFQIdentifier(fqDidStr, ledgers.flatMap(_.namespaces))
      val ledger = ledgers.find(_.namespaces.contains(testIdentifier.namespace)).getOrElse(
        throw new RuntimeException("ledger not found for the namespace: " + testIdentifier.namespace)
      )
      Future.successful(f(ledger))
    } catch {
      case ex: RuntimeException => Future.failed(ex)
    }
  }

  def withLedger[T](ns: Namespace)(f: InMemLedger => T): Future[T] = {
    ledgers.find(_.namespaces.contains(ns)) match {
      case Some(ledger) => Future.successful(f(ledger))
      case None => Future.failed(new RuntimeException("ledger not found for namespace: " + ns))
    }
  }
}

//base interface for any VDR (for testing purposes only)
trait InMemLedger {
  def namespaces: List[Namespace]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDid: VdrDid,
                       endorser: Option[String]): PreparedTxnResult = {
    val id = TestFQIdentifier(fqSchemaId, namespaces)
    val schema = TestVDRSchema(fqSchemaId, schemaJson)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(id.namespace, jsonPayload.getBytes, "", Array.empty, "")
  }

  def prepareCredDefTxn(credDefJson: String,
                        fQCredDefId: FQCredDefId,
                        submitterDID: VdrDid,
                        endorser: Option[String]): PreparedTxnResult = {
    val id = TestFQIdentifier(fQCredDefId, namespaces)
    val credDef = TestVDRCredDef(fQCredDefId, extractSchemaId(credDefJson), credDefJson)
    val jsonPayload = JacksonMsgCodec.toJson(credDef)
    new PreparedTxnResult(id.namespace, jsonPayload.getBytes, "", Array.empty, "")
  }

  def submitTxn(txnBytes: Array[Byte]): TxnResult = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[TestVDRSchema](new String(txnBytes))
        schemas = schemas + (s.schemaId -> s.json.getBytes)

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(txnBytes))
        credDefs = credDefs + (cd.credDefId -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    "{}"
  }

  def resolveSchema(schemaId: FQSchemaId): VdrSchema = {
    val data = schemas.getOrElse(schemaId, throw new RuntimeException("schema not found for given id: " + schemaId))
    new String(data)
  }

  def resolveCredDef(credDefId: FQCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(credDefId, throw new RuntimeException("cred def not found for given id: " + credDefId))
    new String(data)
  }

  def resolveDid(fqDid: FQDid): VdrDid = {
    val data = didDocs.getOrElse(fqDid, throw new RuntimeException("did doc not found for given id: " + fqDid))
    new String(data)
  }

  def addDidDoc(dd: TestVDRDidDoc): Unit = {
    val ddJson = JacksonMsgCodec.toJson(dd)
    didDocs = didDocs + (dd.id -> ddJson.getBytes)
  }

  private def extractSchemaId(json: String): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(json)
    node.get("schemaId").asText()
  }

  private var schemas: Map[FQSchemaId, Payload] = Map.empty
  private var credDefs: Map[FQCredDefId, Payload] = Map.empty
  private var didDocs: Map[FQDid, Payload] = Map.empty

  type Payload = Array[Byte]
}

case class TestIndyLedger(namespaces: List[Namespace],
                          genesisTxnData: String,
                          taaConfig: Option[TaaConfig])
  extends InMemLedger {
}

trait TestPayloadBase {
  def payloadType: String
}

case class TestVDRSchema(schemaId: FQSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "schema"
}

case class TestVDRCredDef(credDefId: FQCredDefId, schemaId: FQSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "creddef"
}

case class TestVDRDidDoc(id: FQDid, verKey: VerKeyStr, endpoint: Option[String])
  extends TestPayloadBase {
  override val payloadType: String = "diddoc"
}

class InvalidIdentifierException(msg: String) extends RuntimeException(msg)