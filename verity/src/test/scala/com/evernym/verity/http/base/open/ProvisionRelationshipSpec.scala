package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.util2.Status.KEY_ALREADY_CREATED
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.{EdgeEndpointBaseSpec, RemoteAgentAndAgencyIdentity}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.testkit.mock.agent.MockEnv

trait ProvisionRelationshipSpec { this : EdgeEndpointBaseSpec =>

  def createNewRelationship(mockEnv: MockEnv, connId: String): Unit = {

    val mockEdgeAgent = mockEnv.edgeAgent

    var ckpm = emptyPackedMsgWrapper
    s"when sent CREATE_KEY msg ($connId)" - {
      "respond with KEY_CREATED msg " taggedAs (UNSAFE_IgnoreLog) in {
        ckpm = mockEdgeAgent.v_0_5_req.prepareCreateKeyMsgForAgency(connId)
        buildAgentPostReq(ckpm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleKeyCreatedResp(PackedMsg(responseAs[Array[Byte]]),
            mockEdgeAgent.buildConnIdMap(connId))
          val remoteDetail = RemoteAgentAndAgencyIdentity(
            mockEdgeAgent.pairwiseConnDetail(connId).myCloudAgentPairwiseDidPair.did,
            mockEdgeAgent.pairwiseConnDetail(connId).myCloudAgentPairwiseDidPair.verKey,
            mockEdgeAgent.senderAgencyDetail.DID,
            mockEdgeAgent.senderAgencyDetail.verKey
          )
          setupAgencyWithRemoteAgentAndAgencyIdentities(mockEnv.othersMockEnv.cloudAgent, remoteDetail)
        }
      }
    }

    s"when sent CREATE_KEY msg again ($connId)" - {
      "should respond with key already created error msg" in {
        buildAgentPostReq(ckpm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(KEY_ALREADY_CREATED)
        }
      }
    }
  }

}
