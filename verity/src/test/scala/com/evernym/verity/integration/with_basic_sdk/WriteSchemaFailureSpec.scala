package com.evernym.verity.integration.with_basic_sdk

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.did.DidStr
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
import com.evernym.verity.ledger.LedgerSvcException
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, CurrentPublicIdentifier, ProblemReport, PublicIdentifier, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.Write
import com.evernym.verity.protocol.testkit.MockLedger
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.vdr.base.{InMemLedger, MOCK_VDR_SOV_NAMESPACE}
import com.evernym.verity.vdr.service.VdrTools
import com.evernym.verity.vdr.{FQCredDefId, FQDid, FQSchemaId, MockIndyLedger, MockLedgerRegistry, Namespace, TxnResult, TxnSpecificParams, VdrCredDef, VdrDid, VdrSchema}

import scala.concurrent.{ExecutionContext, Future}


class WriteSchemaFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  override lazy val defaultSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withVdrTools(new DummyVdrTools(MockLedgerRegistry(List(MockIndyLedger(List(MOCK_VDR_SOV_NAMESPACE), "genesis.txn file path", None))))(futureExecutionContext))

  lazy val issuerVerityApp = VerityEnvBuilder.default().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityApp, executionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    issuerSDK.sendMsg(Create())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    issuerSDK.sendMsg(CurrentPublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifier]()
  }

  "IssuerSdk" - {

    "when sent 'write' (write-schema 0.6) message and ledger returns error" - {
      "should receive 'problem-report'" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age")))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        receivedMsg.msg.message.contains("invalid TAA") shouldBe true
      }
    }
  }

  //in-memory version of VDRTools to be used in tests unit/integration tests
  class DummyVdrTools(ledgerRegistry: MockLedgerRegistry)(implicit ec: ExecutionContext)
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
        //TODO: why we wouldn't have to convert the schema id to fully qualified schema id in main code???
        ledger.prepareSchemaTxn(txnSpecificParams, MockLedger.fqSchemaID(id), submitterDid, endorser)
      }
    }

    override def submitTxn(namespace: Namespace,
                           txnBytes: Array[Byte],
                           signatureSpec: String,
                           signature: Array[Byte],
                           endorsement: String): Future[TxnResult] = {
      Future.failed(LedgerSvcException("invalid TAA"))
    }

    override def resolveDid(fqDid: FQDid): Future[VdrDid] = ???

    override def resolveDid(fqDid: FQDid, cacheOptions: CacheOptions): Future[VdrDid] = ???

    override def resolveSchema(fqSchemaId: FQSchemaId): Future[VdrSchema] = ???

    override def resolveSchema(fqSchemaId: FQSchemaId, cacheOptions: CacheOptions): Future[VdrSchema] = ???

    override def resolveCredDef(fqCredDefId: FQCredDefId): Future[VdrCredDef] = ???

    override def resolveCredDef(fqCredDefId: FQCredDefId, cacheOptions: CacheOptions): Future[VdrCredDef] = ???

    override def prepareCredDef(txnSpecificParams: TxnSpecificParams, submitterDid: DidStr, endorser: Option[String]): Future[PreparedTxnResult] = ???

    override def submitRawTxn(namespace: Namespace, txnBytes: Array[Byte]): Future[TxnResult] = ???

    override def submitQuery(namespace: Namespace, query: String): Future[TxnResult] = ???

    override def prepareDid(txnSpecificParams: TxnSpecificParams, submitterDid: DidStr, endorser: Option[String]): Future[PreparedTxnResult] = ???
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}