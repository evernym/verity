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
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.{Create, NeedsEndorsement, ProblemReport, PublicIdentifierCreated}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.base.PayloadConstants.{SCHEMA, TYPE}
import com.evernym.verity.vdr.{FqCredDefId, MockIndyLedger, MockLedgerRegistry, MockLedgerRegistryBuilder, MockVdrTools, Namespace, TxnResult}
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._


class IssuerSetupEndorsementSpec
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
    Await.result(endorsementEventConsumer.start(), 10.seconds)
  }

  "IssuerSetupProtocol0_7" - {
    "when sent Create message without any active endorserDID" - {
      "should get ProblemReport message" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", None))
        val sigMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        sigMsg.msg.message.contains("No default endorser defined") shouldBe true
      }
    }
    "when sent Create message with an explicit endorserDID" - {
      "should get NeedsEndorsement status" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", Option(EndorserUtil.inactiveEndorserDid)))
        val sigMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        sigMsg.msg shouldBe an[NeedsEndorsement]
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

  "IssuerSetupProtocol" - {

    "when sent Create message with inactive endorser DID" - {
      "should get NeedsEndorsement status" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", Option(EndorserUtil.inactiveEndorserDid)))
        issuerSDK.expectMsgOnWebhook[ProblemReport]()
        val sig = issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
        sig.msg.status shouldBe an[NeedsEndorsement]
      }
    }

    "when sent Create message without any endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", None))
        issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
      }
    }

    "when sent Create message with active endorser DID" - {
      "should be successful" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", Option(EndorserUtil.activeEndorserDid)))
        issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
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
        case SCHEMA  => Future.failed(LedgerRejectException("Not enough ENDORSER signatures"))
        case _       => super.submitTxn(namespace, txnBytes, signatureSpec, signature, endorsement)
      }
    }
  }
}
