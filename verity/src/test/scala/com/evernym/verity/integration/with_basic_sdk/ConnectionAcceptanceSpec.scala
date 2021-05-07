package com.evernym.verity.integration.with_basic_sdk

import akka.http.scaladsl.model.StatusCodes.Unauthorized
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.VerityAppBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.protocols.connecting.common.ConnReqReceived
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{Complete, ConnResponseSent}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation


class ConnectionAcceptanceSpec
  extends VerityAppBaseSpec
    with SdkProvider {

  lazy val verityVAS = setupNewVerityApp()
  lazy val verityCAS = setupNewVerityApp()

  lazy val issuerSDK = setupIssuerSdk(verityVAS.platform)

  lazy val holderSDK1 = setupHolderSdk(verityCAS.platform)
  lazy val holderSDK2 = setupHolderSdk(verityCAS.platform)

  lazy val allVerityApps: List[HttpServer] = List(verityVAS, verityCAS)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  "IssuerSDK" - {
    "when creating new relationship" - {
      "should be successful" in {
        val created = issuerSDK.sendCreateRelationship(firstConn)
        created.did.nonEmpty shouldBe true
        created.verKey.nonEmpty shouldBe true
      }
    }

    "when creating new invitation" - {
      "should be successful" in {
        val invitation = issuerSDK.sendCreateConnectionInvitation(firstConn)
        invitation.inviteURL.nonEmpty shouldBe true
        firstInvitation = invitation
      }
    }
  }

  "HolderSDK1" - {

    "when provisioned cloud agent" - {
      "should be successful" in {
        holderSDK1.fetchAgencyKey()
        val created = holderSDK1.provisionVerityCloudAgent()
        created.selfDID.nonEmpty shouldBe true
        created.agentVerKey.nonEmpty shouldBe true
      }
    }

    "when accepting first invitation" - {
      "should be successful" in {
        holderSDK1.sendCreateNewKey(firstConn)
        holderSDK1.sendConnReqForUnacceptedInvitation(firstConn, firstInvitation)
      }
    }
  }

  "IssuerSDK" - {
    "should receive final 'complete' message" in {
      issuerSDK.expectMsgOnWebhook[ConnReqReceived]
      issuerSDK.expectMsgOnWebhook[ConnResponseSent]
      val receivedMsg = issuerSDK.expectMsgOnWebhook[Complete]
      receivedMsg.msg.theirDid.isEmpty shouldBe false
    }
  }

  "HolderSDK2" - {

    "when provisioned cloud agent" - {
      "should be successful" in {
        holderSDK2.fetchAgencyKey()
        val created = holderSDK2.provisionVerityCloudAgent()
        created.selfDID.nonEmpty shouldBe true
        created.agentVerKey.nonEmpty shouldBe true
      }
    }

    "when try to accept first invitation (already accepted one)" - {
      "should fail with Unauthorized error" in {
        holderSDK2.sendCreateNewKey(firstConn)
        val httpResp = holderSDK2.sendConnReqForAcceptedInvitation(firstConn, firstInvitation)
        httpResp.status shouldBe Unauthorized
      }
    }
  }
}
