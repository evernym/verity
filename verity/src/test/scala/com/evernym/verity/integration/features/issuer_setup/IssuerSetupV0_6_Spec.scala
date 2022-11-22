package com.evernym.verity.integration.features.issuer_setup

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil.{INDY_LEDGER_PREFIX, activeEndorserDid}
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserServiceProvider
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issuersetup.{v_0_6 => issuersetupv0_6}
import com.evernym.verity.protocol.protocols.issuersetup.{v_0_7 => issuersetupv0_7}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class IssuerSetupV0_6_Spec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS: VerityEnv = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVAS, futureExecutionContext)
  lazy val endorserSvcProvider: MockEndorserServiceProvider = MockEndorserServiceProvider(issuerVAS)

  var pubIdentifierCreated: issuersetupv0_6.PublicIdentifierCreated = null

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

    "when sent 'current-public-identifier' (issuer-setup 0.6) message" - {
      "should respond with 'problem-report'" in {
        issuerSDK.sendMsg(issuersetupv0_6.CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[issuersetupv0_6.ProblemReport]()
        val pr = receivedMsg.msg
        pr.message shouldBe "Issuer Identifier has not been created yet"
      }
    }

    "when sent 'current-public-identifier' (issuer-setup 0.7) message" - {
      "should respond with 'problem-report'" in {
        issuerSDK.sendMsg(issuersetupv0_7.CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[issuersetupv0_7.ProblemReport]()
        val pr = receivedMsg.msg
        pr.message shouldBe "Issuer Identifier has not been created yet"
      }
    }

    "when sent 'create' (issuer-setup 0.6) message" - {
      "should respond with 'public-identifier-created'" in {
        issuerSDK.sendMsg(issuersetupv0_6.Create())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[issuersetupv0_6.PublicIdentifierCreated]()
        pubIdentifierCreated = receivedMsg.msg
      }
    }

    "when sent 'create' (issuer-setup 0.7) message" - {
      "should respond with 'public-identifier'" in {
        issuerSDK.sendMsg(issuersetupv0_7.Create("did:indy:sovrin", None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[issuersetupv0_7.PublicIdentifier]().msg
        receivedMsg.did shouldBe pubIdentifierCreated.identifier.did
        receivedMsg.verKey shouldBe pubIdentifierCreated.identifier.verKey
      }
    }
  }
}
