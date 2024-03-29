package com.evernym.verity.integration.base


import com.evernym.verity.actor.PublicIdentityStored
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.IssuerSetup.alreadyCreatingProblem
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, IssuerSetupDefinition, ProblemReport, PublicIdentifierCreated}

class ActorStateModifierSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }


  "IssuerSetup" - {
    "when sent 'create' (issuer-setup 0.6) message" - {
      "should respond with 'public-identifier-created'" in {
        issuerSDK.sendMsg(Create())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
        val pic = receivedMsg.msg
        pic.identifier.did.isEmpty shouldBe false
        pic.identifier.verKey.isEmpty shouldBe false
      }
    }

    "when sent 'create' (issuer-setup 0.6) message again" - {
      "should respond with 'problem-report'" in {
        issuerSDK.sendMsg(Create())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]().msg
        receivedMsg.message.contains(alreadyCreatingProblem)
      }
    }

    "when sent 'create' (issuer-setup 0.6) message after deleting existing state" - {
      "should respond with 'public-identifier-created'" in {
        val userAgentEventMapper: PartialFunction[Any, Option[Any]] = {
          case pis: PublicIdentityStored => None    //this event will be deleted
          case other => Option(other)               //these events will be kept as it is
        }
        modifyUserAgentActorState(issuerVerityEnv, issuerSDK.domainDID, eventMapper = userAgentEventMapper)
        deleteProtocolActorState(issuerVerityEnv, IssuerSetupDefinition, issuerSDK.domainDID, None, None)
        issuerSDK.sendMsg(Create())
        issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]().msg
      }
    }
  }
}
