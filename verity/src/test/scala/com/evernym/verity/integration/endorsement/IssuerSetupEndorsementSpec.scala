package com.evernym.verity.integration.endorsement

import com.evernym.verity.actor.PublicIdentityStored
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.eventing.adapters.basic.consumer.BasicConsumerAdapter
import com.evernym.verity.eventing.adapters.basic.producer.BasicProducerAdapter
import com.evernym.verity.eventing.event_handlers.TOPIC_REQUEST_ENDORSEMENT
import com.evernym.verity.integration.base.{EndorsementReqMsgHandler, EndorserUtil, PortProvider, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerRejectException
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.IssuerSetup.{identifierAlreadyCreatedErrorMsg, identifierNotCreatedProblem}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.{Create, CurrentPublicIdentifier, IssuerSetupDefinition, ProblemReport, PublicIdentifier, PublicIdentifierCreated}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.{FqCredDefId, MockIndyLedger, MockLedgerRegistry, MockLedgerRegistryBuilder, MockVdrTools, Namespace, TxnResult}
import com.evernym.verity.vdr.base.PayloadConstants.{CRED_DEF, TYPE}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.json.JSONObject

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.IterableHasAsJava

class IssuerSetupEndorsementSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder.default()
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
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    Await.result(endorsementEventConsumer.start(), 10.seconds)
  }


  "IssuerSetup" - {
    "EndorserService" - {
      "when published active endorser event" - {
        "should be successful" in {
          Await.result(EndorserUtil.registerActiveEndorser(EndorserUtil.activeEndorserDid, EndorserUtil.indyLedgerLegacyDefaultPrefix, eventProducer), 5.seconds)
        }
      }
    }

    "when sent 'create' (issuer-setup 0.7) message" - {
      "should respond with 'public-identifier-created'" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[JSONObject]()
        val pic = receivedMsg.msg
        pic.getJSONObject("status").has("writtenToLedger") shouldBe true
        pic.getJSONObject("identifier").getString("did").isEmpty shouldBe false
        pic.getJSONObject("identifier").getString("verKey").isEmpty shouldBe false
      }
    }

    "when sent 'create' (issuer-setup 0.7) message again" - {
      "should respond with 'problem-report'" in {
        issuerSDK.sendMsg(Create("did:indy:sovrin", None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]().msg
        receivedMsg.message.contains(identifierAlreadyCreatedErrorMsg)
      }
    }

    "when sent 'currentPublicIdentifier' (issuer-setup 0.7) message after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        val userAgentEventModifier: PartialFunction[Any, Option[Any]] = {
          case pis: PublicIdentityStored => None    //this event will be deleted
          case other => Option(other)               //these events will be kept as it is
        }
        modifyUserAgentActorState(issuerVAS, issuerSDK.domainDID, eventModifier = userAgentEventModifier)
        deleteProtocolActorState(issuerVAS, IssuerSetupDefinition, issuerSDK.domainDID, None, None)
        issuerSDK.sendMsg(CurrentPublicIdentifier())
        issuerSDK.expectMsgOnWebhook[ProblemReport]().msg.message shouldBe identifierNotCreatedProblem
      }
    }

    "when sent 'create' (issuer-setup 0.7) message with inactive endorser after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        val userAgentEventModifier: PartialFunction[Any, Option[Any]] = {
          case pis: PublicIdentityStored => None    //this event will be deleted
          case other => Option(other)               //these events will be kept as it is
        }
        modifyUserAgentActorState(issuerVAS, issuerSDK.domainDID, eventModifier = userAgentEventModifier)
        deleteProtocolActorState(issuerVAS, IssuerSetupDefinition, issuerSDK.domainDID, None, None)
        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(EndorserUtil.inactiveEndorserDid)))
        val msg = issuerSDK.expectMsgOnWebhook[JSONObject]().msg
        msg.getJSONObject("status").has("needsEndorsement") shouldBe true
      }
    }

    "when sent 'create' (issuer-setup 0.7) message with inactive endorser and then sent 'currentPublicIdentifier' after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        val userAgentEventModifier: PartialFunction[Any, Option[Any]] = {
          case pis: PublicIdentityStored => None    //this event will be deleted
          case other => Option(other)               //these events will be kept as it is
        }
        modifyUserAgentActorState(issuerVAS, issuerSDK.domainDID, eventModifier = userAgentEventModifier)
        deleteProtocolActorState(issuerVAS, IssuerSetupDefinition, issuerSDK.domainDID, None, None)
        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(EndorserUtil.inactiveEndorserDid)))
        val msg = issuerSDK.expectMsgOnWebhook[JSONObject]().msg
        msg.getJSONObject("status").has("needsEndorsement") shouldBe true

        issuerSDK.sendMsg(CurrentPublicIdentifier())
        val msg2 = issuerSDK.expectMsgOnWebhook[PublicIdentifier]().msg
        msg2.did.isEmpty shouldBe false
        msg2.verKey.isEmpty shouldBe false
      }
    }

    "when sent 'create' (issuer-setup 0.7) message with active endorser" - {
      "should respond with 'public-identifier-created'" in {
        val userAgentEventModifier: PartialFunction[Any, Option[Any]] = {
          case pis: PublicIdentityStored => None    //this event will be deleted
          case other => Option(other)               //these events will be kept as it is
        }
        modifyUserAgentActorState(issuerVAS, issuerSDK.domainDID, eventModifier = userAgentEventModifier)
        deleteProtocolActorState(issuerVAS, IssuerSetupDefinition, issuerSDK.domainDID, None, None)

        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(EndorserUtil.activeEndorserDid)))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[JSONObject]()
        val pic = receivedMsg.msg
        pic.getJSONObject("status").has("writtenToLedger") shouldBe true
        pic.getJSONObject("identifier").getString("did").isEmpty shouldBe false
        pic.getJSONObject("identifier").getString("verKey").isEmpty shouldBe false
      }
    }
  }


  lazy val endorserServiceEventAdapters: Config =
    issuerVAS.headVerityLocalNode
      .platform.appConfig.config.getConfig("verity.eventing")
      .withValue("verity.eventing.basic-store.http-listener.port", ConfigValueFactory.fromAnyRef(issuerVAS.headVerityLocalNode.portProfile.basicEventStorePort))
      .withValue("verity.eventing.basic-source.id", ConfigValueFactory.fromAnyRef("endorser"))
      .withValue("verity.eventing.basic-source.http-listener.port", ConfigValueFactory.fromAnyRef(PortProvider.getFreePort))
      .withValue("verity.eventing.basic-source.topics", ConfigValueFactory.fromIterable(List(TOPIC_REQUEST_ENDORSEMENT).asJava))

  override lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override lazy val futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}
