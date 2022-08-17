package com.evernym.verity.integration.features.v1tov2migration

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.agent.user.UpdateTheirRouting
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.v1tov2migration.GetUpgradeInfo
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, EAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.testkit.util.HttpUtil


class GetUpgradeInfoSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVAS = VerityEnvBuilder().build(VAS)
  lazy val issuerEAS = VerityEnvBuilder().build(EAS)
  lazy val holderCAS = VerityEnvBuilder().build(CAS)

  lazy val issuerSDKVAS = setupIssuerSdk(issuerVAS, executionContext)
  lazy val issuerSDKEAS = setupIssuerSdk(issuerEAS, executionContext)
  lazy val holderSDKCAS = setupHolderSdk(holderCAS, executionContext, defaultSvcParam.ledgerTxnExecutor)

  val connId = "connId1"
  var invitation: InviteDetail = null

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDKVAS.fetchAgencyKey()

    issuerSDKEAS.fetchAgencyKey()
    issuerSDKEAS.provisionAgent_0_5()
    issuerSDKEAS.updateComMethod_0_5(issuerSDKEAS.msgListener.webhookEndpoint)
    issuerSDKEAS.createKey_0_5(connId)
    invitation = issuerSDKEAS.sendConnReq_0_5(connId).md.inviteDetail

    holderSDKCAS.fetchAgencyKey()
    holderSDKCAS.provisionAgent_0_6()
    holderSDKCAS.createKey_0_6(connId)
    holderSDKCAS.sendConnReqAnswer_0_5(connId, invitation)

    issuerSDKEAS.expectMsgOnWebhook[ConnectionStatusUpdated](mpf = MPF_MSG_PACK)
  }

  "HolderSDK" - {
    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message" - {
      "should be successful" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDKCAS.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid)))
        resp.data.size shouldBe 0
      }
    }

    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message with duplicate pairwise DIDs" - {
      "should be successful" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDKCAS.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid, myPairwiseDid)))
        resp.data.size shouldBe 0
      }
    }

    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message with invalid pairwise DID" - {
      "should fail" in {
        val ex = intercept[RuntimeException] {
          holderSDKCAS.getUpgradeInfo(GetUpgradeInfo(List("garbage")))
        }
        ex.getMessage.contains("no pairwise connection found with these DIDs: garbage") shouldBe true
      }
    }

    "when tried to update their did doc" - {
      "should be successful" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID
        val theirPairwiseDidDoc = holderSDKCAS.myPairwiseRelationships(connId).theirDIDDocReq

        val utr = UpdateTheirRouting(
          theirPairwiseDidDoc.id,
          theirPairwiseDidDoc.verkey,
          issuerSDKVAS.agencyDID,
          issuerSDKVAS.agencyVerKey)
        val jsonReq = DefaultMsgCodec.toJson(utr)
        val apiUrl = holderSDKCAS.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/CAS/connection/$myPairwiseDid/routing")
        val resp = HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        resp.status shouldBe OK
      }
    }

    "when tried to send 'GET_UPGRADE_INFO' post did doc update" - {
      "should respond with upgraded info" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDKCAS.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid)))
        resp.data.size shouldBe 1
        resp.data(myPairwiseDid).direction shouldBe "v1tov2"
        resp.data(myPairwiseDid).theirAgencyEndpoint.startsWith("http") shouldBe true
      }
    }

    "when tried to update their did doc back to original" - {
      "should be successful" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID

        val utr = UpdateTheirRouting(
          invitation.senderDetail.agentKeyDlgProof.get.agentDID,
          invitation.senderDetail.agentKeyDlgProof.get.agentDelegatedKey,
          invitation.senderAgencyDetail.DID,
          invitation.senderAgencyDetail.verKey)
        val jsonReq = DefaultMsgCodec.toJson(utr)
        val apiUrl = holderSDKCAS.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/" +
          s"CAS/connection/$myPairwiseDid/routing")
        val resp = HttpUtil.sendJsonReqToUrl(jsonReq, apiUrl, method = HttpMethods.PUT)(futureExecutionContext)
        resp.status shouldBe OK
      }
    }

    "when tried to send 'GET_UPGRADE_INFO' post did doc update undo" - {
      "should respond no data for upgrade" in {
        val myPairwiseDid = holderSDKCAS.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDKCAS.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid)))
        resp.data.size shouldBe 1
        resp.data(myPairwiseDid).direction shouldBe "v2tov1"
        resp.data(myPairwiseDid).theirAgencyEndpoint.startsWith("http") shouldBe true
      }
    }
  }
}
