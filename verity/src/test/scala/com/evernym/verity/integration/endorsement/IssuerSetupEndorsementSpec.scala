package com.evernym.verity.integration.endorsement

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.{ActorSystemVanilla, MockLedgerTxnExecutor}
import com.evernym.verity.eventing.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.eventing.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.eventing.event_handlers.TOPIC_REQUEST_ENDORSEMENT
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base._
import com.evernym.verity.ledger.{LedgerTxnExecutor, TxnResp}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.{Create, CurrentPublicIdentifier, EndorsementResult, NeedsEndorsement, ProblemReport, PublicIdentifier, PublicIdentifierCreated, RosterInitialized, WrittenToLedger}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigValueFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._


class IssuerSetupEndorsementSpec
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
    Await.result(EndorserUtil.registerActiveEndorser(EndorserUtil.activeEndorserDid, EndorserUtil.indyLedgerLegacyDefaultPrefix, eventProducer), 5.seconds)
  }

  "Issuer Setup Protocol" - {
    "when sent Create message with no endorser" - {
      "should get public identifier created message" in {
        issuerSDK.sendMsg(Create(EndorserUtil.indyLedgerLegacyDefaultPrefix, None))
        issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
      }
    }

    "when sent CurrentPublicIdentifier message where no public identifier has been created" - {
      "should get Problem Report message" in {
        issuerSDK.sendMsg(CurrentPublicIdentifier())
        issuerSDK.expectMsgOnWebhook[ProblemReport]()
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


  val ledgerTxnExecutor: LedgerTxnExecutor = new MockLedgerTxnExecutor(futureExecutionContext)

  override lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override lazy val futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}
