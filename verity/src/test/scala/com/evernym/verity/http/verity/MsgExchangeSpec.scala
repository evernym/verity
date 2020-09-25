package com.evernym.verity.http.verity

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.Status.{DATA_NOT_FOUND, MSG_STATUS_ACCEPTED, MSG_STATUS_RECEIVED, MSG_STATUS_SENT}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CRED, CREATE_MSG_TYPE_CRED_OFFER, CREATE_MSG_TYPE_CRED_REQ}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.http.base.open.{ExpectedMsgCriteria, ExpectedMsgDetail}
import com.evernym.verity.http.common.StatusDetailResp
import org.scalatest.time.{Seconds, Span}

/**
 * tests message exchanges like credentials etc
 */

//TODO: we should add more messages like proof etc here too
trait MsgExchangeSpec { this: VerityEndpointHandlerSpec =>

  def sendCredOffer(): Unit = {
    "when sent CREATE_MSG (cred offer) with invalid replyToMsgId" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEntEdgeAgent1.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_OFFER, PackedMsg("cred-offer-data".getBytes),
          replyToMsgId = Option("123456")).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          val sd = responseTo[StatusDetailResp]
          sd.statusCode shouldBe DATA_NOT_FOUND.statusCode
        }
      }
    }

    "when sent CREATE_MSG (cred offer)" - {
      "should respond with MSG_CREATED" taggedAs (UNSAFE_IgnoreLog)  in {
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockEntEdgeAgent1.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_OFFER,
          PackedMsg("cred-offer-data".getBytes), replyToMsgId = None).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually(timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          mockEntEdgeAgent1.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connIda1))
        }
      }
    }

    testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(totalMsgs = 4,
      List(ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_OFFER, MSG_STATUS_SENT))
    ))
  }

  def testReceivedCredRequest(): Unit = {
    "when received CREATE_MSG (cred req) for last sent cred offer" - {
      "should respond with MSG_CREATED" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_CRED_OFFER)
        buildAgentPostReq(mockConsumerCloudAgent.v_0_5_req.prepareCreateMsgForRemoteAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED_REQ,
          PackedMsg("cred-erq-data".getBytes), replyToMsgId = Option(latestMsg.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(totalMsgs = 5, List(
      ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_OFFER, MSG_STATUS_ACCEPTED),
      ExpectedMsgDetail(CREATE_MSG_TYPE_CRED_REQ, MSG_STATUS_RECEIVED))
    ))

  }

  def testSendCredMsg(): Unit = {
    "when sent CREATE_MSG (cred) in reply to cred req" - {
      "should respond with MSG_CREATED" in {
        val latestMsg = getLatestMsgReq(connIda1, CREATE_MSG_TYPE_CRED_REQ)
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockEntEdgeAgent1.v_0_5_req.prepareCreateGeneralMsgForConnForAgency(connIda1,
          includeSendMsg = true, CREATE_MSG_TYPE_CRED,
          PackedMsg("cred-data".getBytes), replyToMsgId = Option(latestMsg.uid)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          mockEntEdgeAgent1.v_0_5_resp.handleGeneralMsgCreatedResp(PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connIda1))
        }
      }
    }
  }
}
