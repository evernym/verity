package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.VerityAppBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6._


class IssuerSetupActorSpec
  extends VerityAppBaseSpec
    with SdkProvider  {

  lazy val verityVAS = setupNewVerityApp()
  lazy val issuerSDK = setupIssuerSdk(verityVAS.platform)
  lazy val allVerityApps: List[HttpServer] = List(verityVAS)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  "IssuerSdk" - {

    "when sent 'current-public-identifier' message" - {
      "should receive 'problem-report'" in {
        issuerSDK.sendControlMsg(CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]
        receivedMsg.msg.message shouldBe "Issuer Identifier has not been created yet"
      }
    }

    "when sent 'create' message" - {
      "should respond with 'public-identifier-created'" in {
        issuerSDK.sendControlMsg(Create())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]
        val pic = receivedMsg.msg
        pic.identifier.did.isEmpty shouldBe false
        pic.identifier.verKey.isEmpty shouldBe false
      }
    }

    "when sent 'current-public-identifier' message" - {
      "should receive 'public-identifier'" in {
        issuerSDK.sendControlMsg(CurrentPublicIdentifier())
        val receivedMsg = issuerSDK.expectMsgOnWebhook[PublicIdentifier]
        val pi = receivedMsg.msg
        pi.did.isEmpty shouldBe false
        pi.verKey.isEmpty shouldBe false
      }
    }
  }
}
