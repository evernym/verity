package com.evernym.verity.integration.with_basic_sdk

import com.evernym.vdrtools.vdr.VdrParams.CacheOptions
import com.evernym.vdrtools.vdr.VdrResults
import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
import com.evernym.verity.ledger.LedgerSvcException
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, CurrentPublicIdentifier, ProblemReport, PublicIdentifier, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.Write
import com.evernym.verity.vdr.base.{INDY_SOVRIN_NAMESPACE, InMemLedger}
import com.evernym.verity.vdr.{FqCredDefId, FqDID, FqSchemaId, MockIndyLedger, MockLedgerRegistry, MockLedgerRegistryBuilder, MockVdrTools, Namespace, TxnResult, TxnSpecificParams, VdrCredDef, VdrDid, VdrSchema}

import scala.concurrent.Future


class WriteSchemaFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  override lazy val defaultSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withVdrTools(new DummyVdrTools(MockLedgerRegistryBuilder(INDY_SOVRIN_NAMESPACE, MockIndyLedger("genesis.txn file path", None)).build()))

  lazy val issuerVerityApp = VerityEnvBuilder().build(VAS)
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
  class DummyVdrTools(ledgerRegistry: MockLedgerRegistry)
    extends MockVdrTools(ledgerRegistry) {

    //TODO: as we add/integrate actual VDR apis and their tests,
    // this class should evolve to reflect the same for its test implementation

    override def ping(namespaces: List[Namespace]): Future[Map[String, VdrResults.PingResult]] = {
      val allNamespaces = if (namespaces.isEmpty) ledgerRegistry.ledgers.keys else namespaces
      Future.successful(allNamespaces.map(n => n -> new VdrResults.PingResult("0", "SUCCESS")).toMap)
    }

    override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                               submitterDid: FqDID,
                               endorser: Option[String]): Future[PreparedTxnResult] = {
      ledgerRegistry.forLedger(submitterDid) { ledger: InMemLedger =>
        val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
        val id = json.get("id").asText
        ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser)
      }
    }

    override def submitTxn(namespace: Namespace,
                           txnBytes: Array[Byte],
                           signatureSpec: String,
                           signature: Array[Byte],
                           endorsement: String): Future[TxnResult] = {
      Future.failed(LedgerSvcException("invalid TAA"))
    }

    override def resolveDid(fqDid: FqDID): Future[VdrDid] = ???

    override def resolveDid(fqDid: FqDID, cacheOptions: CacheOptions): Future[VdrDid] = ???

    override def resolveSchema(fqSchemaId: FqSchemaId): Future[VdrSchema] = ???

    override def resolveSchema(fqSchemaId: FqSchemaId, cacheOptions: CacheOptions): Future[VdrSchema] = ???

    override def resolveCredDef(fqCredDefId: FqCredDefId): Future[VdrCredDef] = ???

    override def resolveCredDef(fqCredDefId: FqCredDefId, cacheOptions: CacheOptions): Future[VdrCredDef] = ???

    override def prepareCredDef(txnSpecificParams: TxnSpecificParams, submitterDid: FqDID, endorser: Option[String]): Future[PreparedTxnResult] = ???

    override def submitRawTxn(namespace: Namespace, txnBytes: Array[Byte]): Future[TxnResult] = ???

    override def submitQuery(namespace: Namespace, query: String): Future[TxnResult] = ???

    override def prepareDid(txnSpecificParams: TxnSpecificParams, submitterDid: FqDID, endorser: Option[String]): Future[PreparedTxnResult] = ???
  }
}
