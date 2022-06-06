package com.evernym.verity.integration.endorsement

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.eventing.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.eventing.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.eventing.event_handlers.TOPIC_REQUEST_ENDORSEMENT
import com.evernym.verity.integration.base.{EndorsementReqMsgHandler, EndorserUtil, PortProvider, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{NeedsEndorsement, ProblemReport, StatusReport, Write}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.base.{DEFAULT_VDR_NAMESPACE, SOV_LEDGER_NAME}
import com.evernym.verity.vdr.{FqCredDefId, MockIndyLedger, MockLedgerRegistry, MockVdrTools, Namespace, TxnResult}
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._


class WriteSchemaEndorsementSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder
    .default()
    .withVdrTools(dummyVdrTools)
    .build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVAS, futureExecutionContext)

  lazy val eventProducer: BasicProducerAdapter = {
    val actorSystem = ActorSystemVanilla("event-producer", endorserServiceEventAdapters)
    new BasicProducerAdapter(new TestAppConfig(Option(endorserServiceEventAdapters), clearValidators = true))(
      actorSystem, executionContextProvider.futureExecutionContext)
  }

  lazy val endorsementEventConsumer: BasicConsumerAdapter = {
    val testAppConfig = new TestAppConfig(Option(endorserServiceEventAdapters), clearValidators = true)
    val actorSystem = ActorSystemVanilla("event-consumer", endorserServiceEventAdapters)
    new BasicConsumerAdapter(testAppConfig,
      new EndorsementReqMsgHandler(testAppConfig.config, eventProducer))(actorSystem, executionContextProvider.futureExecutionContext)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendMsg(Create())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    Await.result(endorsementEventConsumer.start(), 10.seconds)
  }

  "WriteSchemaProtocol" - {
    "when sent Write message without any active endorserDID" - {
      "should get ProblemReport message" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age")))
        val sigMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        sigMsg.msg.message.contains("No default endorser defined") shouldBe true
      }
    }
    "when sent Write message with an explicit endorserDID" - {
      "should get NeedsEndorsement message" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age"), endorserDID = Option(EndorserUtil.inactiveEndorserDid)))
        issuerSDK.expectMsgOnWebhook[NeedsEndorsement]()
      }
    }
  }

  "EndorserService" - {
    "when published active endorser event" - {
      "should be successful" in {
        Await.result(EndorserUtil.registerActiveEndorser(EndorserUtil.activeEndorserDid, EndorserUtil.indyLedgerLegacyDefaultPrefix, eventProducer), 5.seconds)
      }
    }
  }

  "WriteSchemaProtocol" - {

    "when sent Write message with inactive endorser DID" - {
      "should get NeedsEndorsement message" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age"), endorserDID = Option(EndorserUtil.inactiveEndorserDid)))
        issuerSDK.expectMsgOnWebhook[NeedsEndorsement]()
      }
    }

    "when sent Write message without any endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age")))
        issuerSDK.expectMsgOnWebhook[StatusReport]()
      }
    }

    "when sent Write message with active endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age"), endorserDID = Option(EndorserUtil.activeEndorserDid)))
        issuerSDK.expectMsgOnWebhook[StatusReport]()
      }
    }
  }

  lazy val endorserServiceEventAdapters: Config =
    issuerVAS
      .getVerityLocalNode
      .platform.appConfig.config.getConfig("verity.eventing")
      .withValue("verity.eventing.basic-store.http-listener.port", ConfigValueFactory.fromAnyRef(issuerVAS.getVerityLocalNode.portProfile.basicEventStorePort))
      .withValue("verity.eventing.basic-source.id", ConfigValueFactory.fromAnyRef("endorser"))
      .withValue("verity.eventing.basic-source.http-listener.port", ConfigValueFactory.fromAnyRef(PortProvider.getFreePort))
      .withValue("verity.eventing.basic-source.topics", ConfigValueFactory.fromIterable(List(TOPIC_REQUEST_ENDORSEMENT).asJava))

  val dummyVdrTools = new DummyVdrTools(MockLedgerRegistry(SOV_LEDGER_NAME, List(MockIndyLedger(DEFAULT_VDR_NAMESPACE, List(DEFAULT_VDR_NAMESPACE), "genesis.txn file path", None))))(futureExecutionContext)

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
      node.get("payloadType").asText() match {
        case "schema"  => Future.failed(LedgerRejectException("Not enough ENDORSER signatures"))
        case _          => super.submitTxn(namespace, txnBytes, signatureSpec, signature, endorsement)
      }
    }
  }
}
