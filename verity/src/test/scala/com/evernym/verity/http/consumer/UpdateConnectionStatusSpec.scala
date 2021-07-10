package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.util2.Status.CONN_STATUS_DELETED
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.MockEnv

trait UpdateConnectionStatusSpec { this : ConsumerEndpointHandlerSpec =>

  def testUpdateConnectionStatus(mockEnv: MockEnv): Unit = {
    "when sent UPDATE_CONN_STATUS msg to mark connection 2 as deleted" - {
      "should respond with CONN_STATUS_UPDATED msg" in {
        buildAgentPostReq(mockEnv.edgeAgent.v_0_5_req.prepareUpdateConnStatusMsg(
          connIda2, CONN_STATUS_DELETED.statusCode).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEnv.edgeAgent.v_0_5_resp.handleConnStatusUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_CONN_STATUS 0.6 to delete a connection" - {
      "should be able to CONN_STATUS_UPDATED" in {
        implicit val msgPackagingContext = AgentMsgPackagingContext(MPF_INDY_PACK, MFV_1_0, packForAgencyRoute = true)

        val packedMsg = mockEnv.edgeAgent.v_0_6_req.prepareUpdateConnStatus(CONN_STATUS_DELETED.statusCode, connIda2)
        buildAgentPostReq(packedMsg.msg)  ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }
  }
}
