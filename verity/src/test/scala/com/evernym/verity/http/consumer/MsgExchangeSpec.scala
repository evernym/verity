package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, MSG_STATUS_ACCEPTED, MSG_STATUS_RECEIVED, MSG_STATUS_REVIEWED, MSG_STATUS_SENT}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CRED, CREATE_MSG_TYPE_CRED_OFFER, CREATE_MSG_TYPE_CRED_REQ}
import com.evernym.verity.http.base.open.{ExpectedMsgCriteria, ExpectedMsgDetail}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.testkit.mock.agent.MockEnv
import org.scalatest.time.{Seconds, Span}

/**
 * tests message exchanges like credentials etc
 */

//TODO: we should add more messages like proof etc here too
trait MsgExchangeSpec { this : ConsumerEndpointHandlerSpec =>

  def testReceivedCredOffer(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val othersMockCloudAgent = mockEnv.othersMockEnv.cloudAgent

    "when received CREATE_MSG (cred offer)" - {
      "should respond with MSG_CREATED" in {
        buildAgentPostReq(othersMockCloudAgent.v_0_5_req.prepareCreateMsgForRemoteAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_OFFER,
          PackedMsg("cred-erq-data".getBytes), replyToMsgId = None).msg) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(totalMsgs = 3, List(
        ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_OFFER, MSG_STATUS_RECEIVED))
      )
    )
  }

  def testSendCredRequest(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent

    "when sent CREATE_MSG (cred req) with invalid replyToMsgId" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_REQ, PackedMsg("cred-req-data".getBytes),
          replyToMsgId = Option("123456")).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          val sd = responseTo[StatusDetailResp]
          sd.statusCode shouldBe DATA_NOT_FOUND.statusCode
        }
      }
    }

    "when sent CREATE_MSG (cred req)" - {
      "should respond with MSG_CREATED" in {
        val lastCredOffer = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_CRED_OFFER)
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_REQ,
          PackedMsg("cred-req-data".getBytes), replyToMsgId = Option(lastCredOffer.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          mockEdgeAgent.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connIda1))
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(totalMsgs = 4, List(
        ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_OFFER, MSG_STATUS_ACCEPTED),
        ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_REQ, MSG_STATUS_SENT))
      )
    )
  }

  def testReceivedCred(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val mockOthersCloudAgent = mockEnv.othersMockEnv.cloudAgent

    "when received CREATE_MSG (cred)" - {
      "should respond with MSG_CREATED" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_CRED_REQ)
        buildAgentPostReq(mockOthersCloudAgent.v_0_5_req.prepareCreateMsgForRemoteAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED,
          PackedMsg("cred-erq-data".getBytes), replyToMsgId = Option(latestMsg.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(totalMsgs = 5, List(
        ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_REQ, MSG_STATUS_ACCEPTED),
        ExpectedMsgDetail(CREATE_MSG_TYPE_CRED, MSG_STATUS_RECEIVED))
      )
    )

    "when sent UPDATE_MSG_STATUS for accepted msg" - {
      "should respond with ok" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_CRED_REQ)
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateMsgStatusForConn(connIda1,
          List(latestMsg.uid), MSG_STATUS_REVIEWED.statusCode).msg) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }
  }

}
