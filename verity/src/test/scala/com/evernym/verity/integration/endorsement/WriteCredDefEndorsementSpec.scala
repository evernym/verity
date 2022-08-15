package com.evernym.verity.integration.endorsement

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base._
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil.{activeEndorserDid, INDY_LEDGER_PREFIX}
import com.evernym.verity.integration.base.endorser_svc_provider.{MockEndorserServiceProvider, MockEndorserUtil}
import com.evernym.verity.protocol.engine.asyncapi.vdr.VdrRejectException
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.{NeedsEndorsement, ProblemReport, StatusReport, Write}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{StatusReport => SchemaStatusReport, Write => SchemaWrite}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.base.PayloadConstants.{CRED_DEF, TYPE}
import com.evernym.verity.vdr.{FqCredDefId, MockIndyLedger, MockLedgerRegistry, MockLedgerRegistryBuilder, MockVdrTools, Namespace, TxnResult}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class WriteCredDefEndorsementSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder.default()
    .withVdrTools(dummyVdrTools)
    .build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVAS, futureExecutionContext)
  var schemaId: String = ""
  lazy val endorserSvcProvider: MockEndorserServiceProvider = MockEndorserServiceProvider(issuerVAS)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendMsg(Create())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    issuerSDK.sendMsg(SchemaWrite("name", "1.0", Seq("name", "age")))
    schemaId = issuerSDK.expectMsgOnWebhook[SchemaStatusReport]().msg.schemaId
  }

  "WriteCredDefProtocol" - {
    "when sent Write message without any active endorserDID" - {
      "should get ProblemReport message" in {
        issuerSDK.sendMsg(Write("name", schemaId, None, None))
        val sigMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        sigMsg.msg.message.contains("No default endorser defined") shouldBe true
      }
    }
    "when sent Write message with an explicit endorserDID" - {
      "should get NeedsEndorsement message" in {
        issuerSDK.sendMsg(Write("name", schemaId, None, None, endorserDID = Option(MockEndorserUtil.inactiveEndorserDid)))
        issuerSDK.expectMsgOnWebhook[NeedsEndorsement](timeout = Duration(1, SECONDS))
      }
    }
  }

  "EndorserService" - {
    "when published active endorser event" - {
      "should be successful" in {
        Await.result(endorserSvcProvider.publishEndorserActivatedEvent(activeEndorserDid, INDY_LEDGER_PREFIX), 5.seconds)
      }
    }
  }

  "WriteCredDefProtocol" - {

    "when sent Write message with inactive endorser DID" - {
      "should get NeedsEndorsement message" in {
        issuerSDK.sendMsg(Write("name", schemaId, None, None, endorserDID = Option(MockEndorserUtil.inactiveEndorserDid)))
        issuerSDK.expectMsgOnWebhook[NeedsEndorsement]()
      }
    }

    "when sent Write message without any endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Write("name", schemaId, None, None))
        issuerSDK.expectMsgOnWebhook[StatusReport]()
      }
    }

    "when sent Write message with active endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Write("name", schemaId, None, None, endorserDID = Option(MockEndorserUtil.activeEndorserDid)))
        issuerSDK.expectMsgOnWebhook[StatusReport]()
      }
    }
  }

  val dummyVdrTools = new DummyVdrTools(MockLedgerRegistryBuilder(Map(INDY_SOVRIN_NAMESPACE -> MockIndyLedger("genesis.txn file path", None))).build())(futureExecutionContext)

  override lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override lazy val futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext

  class DummyVdrTools(ledgerRegistry: MockLedgerRegistry)(implicit ec: ExecutionContext)
    extends MockVdrTools(ledgerRegistry) {

    override def submitTxn(namespace: Namespace,
                           txnBytes: Array[Byte],
                           signatureSpec: FqCredDefId,
                           signature: Array[Byte],
                           endorsement: FqCredDefId): Future[TxnResult] = {
      val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
      node.get(TYPE).asText() match {
        case CRED_DEF  => Future.failed(VdrRejectException("Not enough ENDORSER signatures"))
        case _         => super.submitTxn(namespace, txnBytes, signatureSpec, signature, endorsement)
      }
    }
  }
}
