package com.evernym.verity.integration.endorsement

import com.evernym.verity.actor.PublicIdentityStored
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil.{activeEndorserDid, INDY_LEDGER_PREFIX}
import com.evernym.verity.integration.base.endorser_svc_provider.{MockEndorserServiceProvider, MockEndorserUtil}
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.IssuerSetup.{identifierAlreadyCreatedErrorMsg, identifierNotCreatedProblem}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.{Create, CurrentPublicIdentifier, IssuerSetupDefinition, ProblemReport, PublicIdentifier}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import org.json.JSONObject

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class IssuerSetupEndorsementSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVAS, futureExecutionContext)
  lazy val endorserSvcProvider: MockEndorserServiceProvider = MockEndorserServiceProvider(issuerVAS)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }


  "IssuerSetup" - {
    "EndorserService" - {
      "when published active endorser event" - {
        "should be successful" in {
          Await.result(endorserSvcProvider.publishEndorserActivatedEvent(activeEndorserDid, INDY_LEDGER_PREFIX), 5.seconds)
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
        resetIssuerSetupChanges()
        issuerSDK.sendMsg(CurrentPublicIdentifier())
        issuerSDK.expectMsgOnWebhook[ProblemReport]().msg.message shouldBe identifierNotCreatedProblem
      }
    }

    "when sent 'create' (issuer-setup 0.7) message with inactive endorser after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        resetIssuerSetupChanges()
        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(MockEndorserUtil.inactiveEndorserDid)))
        val msg = issuerSDK.expectMsgOnWebhook[JSONObject]().msg
        msg.getJSONObject("status").has("needsEndorsement") shouldBe true
      }
    }

    "when sent 'create' (issuer-setup 0.7) message with inactive endorser and then sent 'currentPublicIdentifier' after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        resetIssuerSetupChanges()
        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(MockEndorserUtil.inactiveEndorserDid)))
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
        resetIssuerSetupChanges()
        issuerSDK.sendMsg(Create("did:indy:sovrin", Some(MockEndorserUtil.activeEndorserDid)))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[JSONObject]()
        val pic = receivedMsg.msg
        pic.getJSONObject("status").has("writtenToLedger") shouldBe true
        pic.getJSONObject("identifier").getString("did").isEmpty shouldBe false
        pic.getJSONObject("identifier").getString("verKey").isEmpty shouldBe false
      }
    }
  }

  private def resetIssuerSetupChanges(): Unit = {
    val userAgentEventMapper: PartialFunction[Any, Option[Any]] = {
      case _: PublicIdentityStored => None  //this event will be deleted
      case other => Option(other)           //these events (whatever they maybe) will be kept as it is
    }
    modifyUserAgentActorState(issuerVAS, issuerSDK.domainDID, eventMapper = userAgentEventMapper)
    deleteProtocolActorState(issuerVAS, IssuerSetupDefinition, issuerSDK.domainDID, None, None)
  }

  override lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override lazy val futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}
