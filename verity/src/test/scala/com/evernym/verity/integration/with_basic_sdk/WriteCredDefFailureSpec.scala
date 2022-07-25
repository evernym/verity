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
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6._
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{StatusReport => WriteSchemaStatusReport, Write => WriteSchema}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.{Write => WriteCredDef}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.vdr.base.PayloadConstants.{CRED_DEF, TYPE}
import com.evernym.verity.vdr.base.{INDY_SOVRIN_NAMESPACE, InMemLedger}
import com.evernym.verity.vdr.service.VDRAdapterUtil
import com.evernym.verity.vdr.{FqCredDefId, FqDID, FqSchemaId, MockIndyLedger, MockLedgerRegistry, MockVdrTools, Namespace, TxnResult, TxnSpecificParams, VdrCredDef, VdrDid, VdrSchema}

import scala.concurrent.{ExecutionContext, Future}


class WriteCredDefFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  override lazy val defaultSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withVdrTools(new DummyVdrTools(MockIndyLedger("genesis.txn file path", None))(futureExecutionContext))

  lazy val issuerVerityApp = VerityEnvBuilder.default().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityApp, executionContext)
  var schemaId: String = ""

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
    issuerSDK.sendMsg(WriteSchema("name", "1.0", Seq("name", "age")))
    val sr = issuerSDK.expectMsgOnWebhook[WriteSchemaStatusReport]()
    schemaId = sr.msg.schemaId
  }

  "IssuerSdk" - {

    "when sent 'write' (write-cred-def 0.6) message and ledger returns error" - {
      "should receive 'status-report'" in {
        issuerSDK.sendMsg(WriteCredDef("name", schemaId, None, None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        receivedMsg.msg.message.contains("invalid TAA") shouldBe true
      }
    }
  }

  //in-memory version of VDRTools to be used in tests unit/integration tests
  class DummyVdrTools(ledger: InMemLedger)(implicit ec: ExecutionContext)
    extends MockVdrTools(new MockLedgerRegistry(Map(INDY_SOVRIN_NAMESPACE -> ledger))) {

    //TODO: as we add/integrate actual VDR apis and their tests,
    // this class should evolve to reflect the same for its test implementation

    override def ping(namespaces: List[Namespace]): Future[Map[String, VdrResults.PingResult]] = {
      Future.successful(
        namespaces.map { namespace =>
          namespace -> new VdrResults.PingResult("0", "SUCCESS")
        }.toMap
      )
    }

    override def prepareSchema(txnSpecificParams: TxnSpecificParams,
                               submitterDid: FqDID,
                               endorser: Option[String]): Future[PreparedTxnResult] = {
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams)
      val id = json.get(VDRAdapterUtil.ID).asText
      Future.successful(ledger.prepareSchemaTxn(txnSpecificParams, id, submitterDid, endorser))
    }

    override def submitTxn(namespace: Namespace,
                           txnBytes: Array[Byte],
                           signatureSpec: String,
                           signature: Array[Byte],
                           endorsement: String): Future[TxnResult] = {
      val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
      node.get(TYPE).asText() match {
        case CRED_DEF  => Future.failed(LedgerSvcException("invalid TAA"))
        case _          => Future.successful(ledger.submitTxn(txnBytes))
      }
    }

    override def resolveDid(fqDid: FqDID): Future[VdrDid] = ???

    override def resolveDid(fqDid: FqDID,
                            cacheOptions: CacheOptions): Future[VdrDid] = ???

    override def resolveSchema(fqSchemaId: FqSchemaId): Future[VdrSchema] = {
      Future.successful(ledger.resolveSchema(fqSchemaId))
    }

    override def resolveSchema(fqSchemaId: FqSchemaId,
                               cacheOptions: CacheOptions): Future[VdrSchema] = {
      Future.successful(ledger.resolveSchema(fqSchemaId))
    }

    override def resolveCredDef(fqCredDefId: FqCredDefId): Future[VdrCredDef] = ???

    override def resolveCredDef(fqCredDefId: FqCredDefId,
                                cacheOptions: CacheOptions): Future[VdrCredDef] = ???

    override def prepareDid(txnSpecificParams: TxnSpecificParams,
                            submitterDid: FqDID,
                            endorser: Option[String]): Future[PreparedTxnResult] = ???

    override def prepareCredDef(txnSpecificParams: TxnSpecificParams,
                                submitterDid: FqDID,
                                endorser: Option[String]): Future[PreparedTxnResult] = {
      val json = JacksonMsgCodec.docFromStrUnchecked(txnSpecificParams);
      val id = json.get(VDRAdapterUtil.ID).asText()
      Future.successful(ledger.prepareCredDefTxn(txnSpecificParams, id, submitterDid, endorser))
    }

    override def submitRawTxn(namespace: Namespace, txnBytes: Array[Byte]): Future[TxnResult] = ???

    override def submitQuery(namespace: Namespace, query: String): Future[TxnResult] = ???
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
