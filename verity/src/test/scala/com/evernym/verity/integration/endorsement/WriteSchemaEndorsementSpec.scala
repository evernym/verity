package com.evernym.verity.integration.endorsement

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.{ActorSystemVanilla, MockLedgerTxnExecutor}
import com.evernym.verity.event_bus.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.event_bus.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.event_bus.event_handlers.TOPIC_SSI_ENDORSEMENT_REQ
import com.evernym.verity.integration.base.{EndorsementReqMsgHandler, EndorserUtil, PortProvider, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.ledger.{LedgerTxnExecutor, TxnResp}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{NeedsEndorsement, StatusReport, Write}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._


class WriteSchemaEndorsementSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder.default().withLedgerTxnExecutor(ledgerTxnExecutor).build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVAS, futureExecutionContext)

  lazy val eventProducer: BasicProducerAdapter = {
    val actorSystem = ActorSystemVanilla("event-producer", endorserServiceEventAdapters)
    new BasicProducerAdapter(new TestAppConfig(Option(endorserServiceEventAdapters), clearValidators = true))(
      actorSystem, executionContextProvider.futureExecutionContext)
  }

  lazy val endorsementEventConsumer: BasicConsumerAdapter = {
    val actorSystem = ActorSystemVanilla("event-consumer", endorserServiceEventAdapters)
    new BasicConsumerAdapter(new TestAppConfig(Option(endorserServiceEventAdapters), clearValidators = true),
      new EndorsementReqMsgHandler(eventProducer))(actorSystem, executionContextProvider.futureExecutionContext)
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

  "EndorserService" - {
    "when published active endorser event" - {
      "should be successful" in {
        Await.result(EndorserUtil.registerActiveEndorser(EndorserUtil.activeEndorserDid, eventProducer), 5.seconds)
      }
    }
  }

  "WriteSchemaProtocol" - {
    "when sent Write message" - {
      "should be successful" in {
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
    issuerVAS.getVerityLocalNode.platform.appConfig.config.getConfig("verity.event-bus")
      .withValue("verity.event-bus.basic.store.http-listener.port", ConfigValueFactory.fromAnyRef(issuerVAS.getVerityLocalNode.portProfile.basicEventStorePort))
      .withValue("verity.event-bus.basic.consumer.id", ConfigValueFactory.fromAnyRef("endorser"))
      .withValue("verity.event-bus.basic.consumer.http-listener.port", ConfigValueFactory.fromAnyRef(PortProvider.getFreePort))
      .withValue("verity.event-bus.basic.consumer.topics", ConfigValueFactory.fromIterable(List(TOPIC_SSI_ENDORSEMENT_REQ).asJava))
      .withValue("verity.event-bus.basic.producer.http-listener.port", ConfigValueFactory.fromAnyRef(PortProvider.getFreePort))


  val ledgerTxnExecutor: LedgerTxnExecutor = new MockLedgerTxnExecutor(futureExecutionContext) {

    override def writeSchema(submitterDID: DID,
                             schemaJson: String,
                             walletAccess: WalletAccess): Future[TxnResp] = {
      Future.failed(LedgerRejectException(s"verkey for $submitterDID cannot be found"))
    }
  }

  override lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override lazy val futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}
