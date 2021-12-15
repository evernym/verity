package com.evernym.verity.integration.v1tov2migration

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.agentmsg.msgfamily.v1tov2migration.GetUpgradeInfo
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, EAS, VerityProviderBaseSpec}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext


class GetUpgradeInfoSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(EAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext)

  val connId = "connId1"

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionAgent_0_5()
    issuerSDK.updateComMethod_0_5(issuerSDK.msgListener.webhookEndpoint)
    issuerSDK.createKey_0_5(connId)
    val invitation = issuerSDK.sendConnReq_0_5(connId).md.inviteDetail

    holderSDK.fetchAgencyKey()
    holderSDK.provisionAgent_0_6()
    holderSDK.createKey_0_6(connId)
    holderSDK.sendConnReqAnswer_0_5(connId, invitation)

    issuerSDK.expectMsgOnWebhook[ConnectionStatusUpdated](mpf = MPF_MSG_PACK)
  }

  "HolderSDK" - {
    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message" - {
      "should be successful" in {
        val myPairwiseDid = holderSDK.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDK.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid)))
        resp.data.size shouldBe 0
      }
    }
    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message with duplicate pairwise DIDs" - {
      "should be successful" in {
        val myPairwiseDid = holderSDK.myPairwiseRelationships(connId).myPairwiseDID
        val resp = holderSDK.getUpgradeInfo(GetUpgradeInfo(List(myPairwiseDid, myPairwiseDid)))
        resp.data.size shouldBe 0
      }
    }
    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message with invalid pairwise DID" - {
      "should fail" in {
        val ex = intercept[RuntimeException] {
          holderSDK.getUpgradeInfo(GetUpgradeInfo(List("garbage")))
        }
        ex.getMessage.contains("no pairwise connection found with these DIDs: garbage") shouldBe true
      }
    }
  }

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureExecutionContext: ExecutionContext = executionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}
