package com.evernym.verity.integration.with_basic_sdk


import akka.http.scaladsl.model.StatusCodes.Unauthorized
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation


class ConnectionAcceptanceSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build()
  lazy val holderVerityEnv = VerityEnvBuilder.default().build()

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)

  lazy val holderSDK1 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)
  lazy val holderSDK2 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  val firstConn = "connId1"
  var firstInvitation: Invitation = _
  var lastReceivedThread: Option[MsgThread] = None

  "IssuerSDK" - {
    "when sent 'create' (relationship 1.0) message" - {
      "should be successful" in {
        val receivedMsg = issuerSDK.sendCreateRelationship(firstConn)
        val created = receivedMsg.msg
        created.did.nonEmpty shouldBe true
        created.verKey.nonEmpty shouldBe true
        lastReceivedThread = receivedMsg.threadOpt
      }
    }

    "when sent 'connection-invitation' (relationship 1.0) message" - {
      "should be successful" in {
        val invitation = issuerSDK.sendCreateConnectionInvitation(firstConn, lastReceivedThread)
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
        holderSDK1.sendConnReqForInvitation(firstConn, firstInvitation)
      }
    }
  }

  "IssuerSDK" - {
    "should receive final 'complete' (connections 1.0) message" in {
      val complete = issuerSDK.expectConnectionComplete(firstConn)
      complete.theirDid.isEmpty shouldBe false
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
