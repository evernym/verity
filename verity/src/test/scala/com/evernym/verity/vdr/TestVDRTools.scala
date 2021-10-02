package com.evernym.verity.vdr

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service._

import scala.concurrent.{ExecutionContext, Future}

//in-memory version of VDRTools to be used in tests (unit/integration)
class TestVDRTools(implicit ec: ExecutionContext)
  extends VDRTools {

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  override def registerIndyLedger(namespaces: List[Namespace],
                                  genesisTxnFilePath: String,
                                  taaConfig: Option[TAAConfig]): Future[VDR_LedgerRegistered] = {
    ledgerRegistry = ledgerRegistry.withNewLedger(TestIndyLedger(namespaces, genesisTxnFilePath, taaConfig))
    Future.successful(VDR_LedgerRegistered())
  }

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: String,
                                submitterDid: DidStr,
                                endorser: Option[String]): Future[VDR_PreparedTxn] = {
    forLedger(submitterDid) { ledger: InMemLedger =>
      ledger.prepareSchemaTxn(schemaJson, fqSchemaId, submitterDid, endorser)
    }
  }

  override def submitTxn(preparedTxn: VDR_PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte]): Future[VDR_SubmittedTxn] = {
    withLedger(preparedTxn.context) { ledger: InMemLedger =>
      ledger.submitTxn(preparedTxn, signature, endorsement)
    }
  }

  override def resolveSchema(schemaId: FQSchemaId): Future[VDR_Schema] = {
    forLedger(schemaId) { ledger: InMemLedger =>
      ledger.resolveSchema(schemaId)
    }
  }

  //--helper functions

  private def forLedger[T](fqDidStr: DidStr)(f: InMemLedger => T): Future[T] = {
    try {
      val testIdentifier = TestFQIdentifier(fqDidStr, ledgerRegistry.ledgers.flatMap(_.namespaces))
      val ledger = ledgerRegistry.ledgers.find(_.namespaces.contains(testIdentifier.namespace)).getOrElse(
        throw new RuntimeException("ledger not found for the namespace: " + testIdentifier.namespace)
      )
      Future(f(ledger))
    } catch {
      case _: RuntimeException =>
        Future.failed(new RuntimeException("invalid fq did: " + fqDidStr))
    }
  }

  private def withLedger[T](id: String)(f: InMemLedger => T): Future[T] = {
    ledgerRegistry.ledgers.find(_.id == id) match {
      case Some(ledger) => Future(f(ledger))
      case None         => Future.failed(new RuntimeException("ledger not found with id: " + id))
    }

  }

  private var ledgerRegistry: TestLedgerRegistry = TestLedgerRegistry(List.empty)
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
        case None => throw new RuntimeException("invalid identifier: " + fqId)
      }
    } else {
      throw new RuntimeException("invalid identifier: " + fqId)
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
                       submitterDid: DidStr,
                       endorser: Option[String]): VDR_PreparedTxn = {
    TestFQIdentifier(fqSchemaId, namespaces)
    val schema = TestVDRSchema(fqSchemaId, schemaJson)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    VDR_PreparedTxn(id, VDR_NoSignature, jsonPayload.getBytes, VDR_NoEndorsement)
  }

  def submitTxn(preparedTxn: VDR_PreparedTxn,
                signature: Array[Byte],
                endorsement: Array[Byte]): VDR_SubmittedTxn = {
    val schema = JacksonMsgCodec.fromJson[TestVDRSchema](new String(preparedTxn.bytesToSign))
    schemas = schemas + (schema.schemaId -> schema.json.getBytes)
    VDR_SubmittedTxn()
  }

  def resolveSchema(schemaId: FQSchemaId): VDR_Schema = {
    VDR_Schema(schemaId, schemas(schemaId))
  }

  private var schemas: Map[FQSchemaId, Payload] = Map.empty

  type Payload = Array[Byte]
}

case class TestIndyLedger(namespaces: List[Namespace],
                          genesisTxnFilePath: String,
                          taaConfig: Option[TAAConfig])
  extends InMemLedger {
  override def id: String = namespaces.mkString("-")
}

case class TestVDRSchema(schemaId: String, json: String)