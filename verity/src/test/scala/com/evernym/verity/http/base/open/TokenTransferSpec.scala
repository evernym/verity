package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.Status.MSG_STATUS_SENT
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_TOKEN_XFERRED, CREATE_MSG_TYPE_TOKEN_XFER_OFFER, CREATE_MSG_TYPE_TOKEN_XFER_REQ}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent

trait TokenTransferSpec { this : EndpointHandlerBaseSpec =>

  def mockEdgeAgent: MockEdgeAgent

  def testTokenTransferSpec(): Unit = {

    "when sent CREATE_MSG (tokenTransferOffer)" - {
      "should respond MSG_CREATED msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(
          connIda1, includeSendMsg = true, CREATE_MSG_TYPE_TOKEN_XFER_OFFER,
          PackedMsg("token-transfer-offer-data".getBytes),
          replyToMsgId = None).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(List(
      ExpectedMsgDetail(CREATE_MSG_TYPE_TOKEN_XFER_OFFER, MSG_STATUS_SENT))
    ))

    "when sent CREATE_MSG (tokenTransferReq)" - {
      s"should respond MSG_CREATED msg" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_TOKEN_XFER_OFFER)
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_TOKEN_XFER_REQ,
          PackedMsg("token-transfer-req-data".getBytes),
          replyToMsgId = Option(latestMsg.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(List(
      ExpectedMsgDetail(CREATE_MSG_TYPE_TOKEN_XFER_REQ, MSG_STATUS_SENT))
    ))

    "when sent CREATE_MSG (tokenTransferred)" - {
      s"should respond MSG_CREATED msg" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_TOKEN_XFER_REQ)
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_TOKEN_XFERRED,
          PackedMsg("token-transferred-data".getBytes),
          replyToMsgId = Option(latestMsg.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }
  }
}
