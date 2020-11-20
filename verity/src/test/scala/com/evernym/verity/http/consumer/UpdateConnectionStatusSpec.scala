package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.Status.CONN_STATUS_DELETED
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

trait UpdateConnectionStatusSpec { this : ConsumerEndpointHandlerSpec =>

  def testUpdateConnectionStatus(): Unit = {
    "when sent UPDATE_CONN_STATUS msg to mark connection 2 as deleted" - {
      "should respond with CONN_STATUS_UPDATED msg" in {
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareUpdateConnStatusMsg(
          connIda2, CONN_STATUS_DELETED.statusCode).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockConsumerEdgeAgent1.v_0_5_resp.handleConnStatusUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_CONN_STATUS 0.6 to delete a connection" - {
      "should be able to CONN_STATUS_UPDATED" in {
        implicit val msgPackagingContext = AgentMsgPackagingContext(MPF_INDY_PACK, MFV_1_0, packForAgencyRoute = true)

        val packedMsg = mockEdgeAgent.v_0_6_req.prepareUpdateConnStatus(CONN_STATUS_DELETED.statusCode, connIda2)
        buildAgentPostReq(packedMsg.msg)  ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }
  }
}
