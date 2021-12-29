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
    Future.successful(allNamespaces.map(n => n -> new VdrResults.PingResult("SUCCESS", "OK")).toMap)
  }

  override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                             submitterDid: DidStr,
                             endorser: Option[String]): Future[PreparedTxnResult] = {
    forLedger(submitterDid) { ledger: InMemLedger =>
      ledger.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDid, endorser)
    }
  }

  override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                              submitterDid: DidStr,
                              endorser: Option[String]): Future[PreparedTxnResult] = {
    forLedger(submitterDid) { ledger: InMemLedger =>
      ledger.prepareCredDefTxn(credDefJson, fqCredDefId, submitterDID, endorser)
    }
  }

  override def submitTxn(namespace: Namespace,
                         txnBytes: Array[Byte],
                         signatureSpec: String,
                         signature: Array[Byte],
                         endorsement: String): Future[TxnResult] = {
    withLedger(preparedTxn.context) { ledger: InMemLedger =>
      ledger.submitTxn(preparedTxn, signature, endorsement)
    }
  }

  def submitRawTxn(namespace: Namespace,
                   txnBytes: Array[Byte]): Future[TxnResult] = ???

  def submitQuery(namespace: Namespace,
                  query: String): Future[TxnResult] = ???

  override def resolveSchema(schemaId: FQSchemaId): Future[VdrSchema] = {
    forLedger(schemaId) { ledger: InMemLedger =>
      ledger.resolveSchema(schemaId)
    }
  }

  override def resolveSchema(schemaId: FQSchemaId,
                             cacheOptions: CacheOptions): Future[VdrSchema] = {
    forLedger(schemaId) { ledger: InMemLedger =>
      ledger.resolveSchema(schemaId)
    }
  }

  override def resolveCredDef(credDefId: FQCredDefId): Future[VdrCredDef] = {
    forLedger(credDefId) { ledger: InMemLedger =>
      ledger.resolveCredDef(credDefId)
    }
  }

  override def resolveCredDef(credDefId: FQCredDefId,
                              cacheOptions: CacheOptions): Future[VdrCredDef] = {
    forLedger(credDefId) { ledger: InMemLedger =>
      ledger.resolveCredDef(credDefId)
    }
  }

  override def resolveDid(fqDid: FQDid): Future[VdrDid] = {
    forLedger(fqDid) { ledger: InMemLedger =>
      ledger.resolveDid(fqDid)
    }
  }

  override def resolveDid(fqDid: FQDid,
                          cacheOptions: CacheOptions): Future[VdrDid] = {
    forLedger(fqDid) { ledger: InMemLedger =>
      ledger.resolveDid(fqDid)
    }
  }


  //--helper functions

  def addDidDoc(dd: TestVDRDidDoc): Future[Unit] = {
    forLedger(dd.id) { ledger: InMemLedger =>
      ledger.addDidDoc(dd)
    }
  }

  private def forLedger[T](fqDidStr: DidStr)(f: InMemLedger => T): Future[T] = {
    try {
      val testIdentifier = TestFQIdentifier(fqDidStr, ledgerRegistry.ledgers.flatMap(_.namespaces))
      val ledger = ledgerRegistry.ledgers.find(_.namespaces.contains(testIdentifier.namespace)).getOrElse(
        throw new RuntimeException("ledger not found for the namespace: " + testIdentifier.namespace)
      )
      Future.successful(f(ledger))
    } catch {
      case ex: RuntimeException => Future.failed(ex)
    }
  }

  private def withLedger[T](id: String)(f: InMemLedger => T): Future[T] = {
    ledgerRegistry.ledgers.find(_.id == id) match {
      case Some(ledger) => Future.successful(f(ledger))
      case None => Future.failed(new RuntimeException("ledger not found with id: " + id))
    }
  }

  //private var ledgerRegistry: TestLedgerRegistry = TestLedgerRegistry(List.empty)
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

case class TestLedgerRegistry(ledgers: List[InMemLedger]) {
  def withNewLedger(ledger: InMemLedger): TestLedgerRegistry = {
    copy(ledgers :+ ledger)
  }
}

//base interface for any VDR (for testing purposes only)
trait InMemLedger {
  def id: String

  def namespaces: List[Namespace]

  def prepareSchemaTxn(schemaJson: String,
                       fqSchemaId: FQSchemaId,
                       submitterDid: VdrDid,
                       endorser: Option[String]): PreparedTxnResult = {
    TestFQIdentifier(fqSchemaId, namespaces)
    val schema = TestVDRSchema(fqSchemaId, schemaJson)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    VDR_PreparedTxn(id, VDR_NoSignature, jsonPayload.getBytes, VDR_NoEndorsement)
  }

  def prepareCredDefTxn(credDefJson: String,
                        fQCredDefId: FQCredDefId,
                        submitterDID: VdrDid,
                        endorser: Option[String]): PreparedTxnResult = {
    TestFQIdentifier(fQCredDefId, namespaces)
    val credDef = TestVDRCredDef(fQCredDefId, extractSchemaId(credDefJson), credDefJson)
    val jsonPayload = JacksonMsgCodec.toJson(credDef)
    VDR_PreparedTxn(id, VDR_NoSignature, jsonPayload.getBytes, VDR_NoEndorsement)
  }

  def submitTxn(preparedTxn: VDR_PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): TxnResult = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(preparedTxn.bytesToSign))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[TestVDRSchema](new String(preparedTxn.bytesToSign))
        schemas = schemas + (s.schemaId -> s.json.getBytes)

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(preparedTxn.bytesToSign))
        credDefs = credDefs + (cd.credDefId -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    VDR_SubmittedTxn()
  }

  def resolveSchema(schemaId: FQSchemaId): VdrSchema = {
    val data = schemas.getOrElse(schemaId, throw new RuntimeException("schema not found for given id: " + schemaId))
    VDR_Schema(schemaId, data)
  }

  def resolveCredDef(credDefId: FQCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(credDefId, throw new RuntimeException("cred def not found for given id: " + credDefId))
    val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(data))
    VDR_CredDef(credDefId, cd.schemaId, data)
  }

  def resolveDid(fqDid: FQDid): VdrDid = {
    val data = didDocs.getOrElse(fqDid, throw new RuntimeException("did doc not found for given id: " + fqDid))
    val dd = JacksonMsgCodec.fromJson[TestVDRDidDoc](new String(data))
    VDR_DidDoc(fqDid, dd.verKey, dd.endpoint)
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
  override def id: String = namespaces.mkString("-")
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