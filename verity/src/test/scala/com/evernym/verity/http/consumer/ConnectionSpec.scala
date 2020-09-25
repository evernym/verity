package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants.PUSH_COM_METHOD
import com.evernym.verity.Status.{MISSING_REQ_FIELD, MSG_STATUS_ACCEPTED, PAIRWISE_KEYS_ALREADY_IN_WALLET, SIGNATURE_VERIF_FAILED}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.http.base.open.{ExpectedMsgCriteria, ExpectedMsgDetail}
import org.scalatest.time.{Seconds, Span}

trait ConnectionSpec { this : ConsumerEndpointHandlerSpec =>

  def testAnswerFirstInvitation(): Unit = {

    "provisions new relationship" - {
      createNewRelationship(connIda1)
      setInviteData(connIda1, mockEntEdgeAgent1, mockEntCloudAgent)
      testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(totalMsgs = 0))
    }

    testInvalidInviteAnswers()

    "when sent CREATE_MSG (conn req answer)" - {
      "should respond with success for create invite answer msg" in {
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda1, includeSendMsg = true,
          mockEntEdgeAgent1.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          val resp = mockConsumerEdgeAgent1.v_0_5_resp.handleInviteAnswerCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testGetMsgsFromConnection(connIda1, ExpectedMsgCriteria(totalMsgs = 2, List(
      ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ, MSG_STATUS_ACCEPTED),
      ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ_ANSWER, MSG_STATUS_ACCEPTED))
    ))

    testGetMsgsByConnections(1, Map(connIda1 -> ExpectedMsgCriteria(2)))

  }

  private def testInvalidInviteAnswers(): Unit = {
    "when sent CREATE_MSG (conn req answer) with wrong agent key dlg proof provided" - {
      "should respond with bad request error msg" in {
        val msg = mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda1, includeSendMsg = false,
          buildInvalidRemoteAgentKeyDlgProof(mockEntEdgeAgent1.pairwiseConnDetail(connIda1).lastSentInvite)).msg
        buildAgentPostReq(msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(SIGNATURE_VERIF_FAILED.withMessage(
            "remote agent key delegation proof verification failed"))
        }
      }
    }

    "when sent CREATE_MSG (conn req answer) with no key dlg proof" - {
      "should respond with bad request error msg" in {
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgWithoutKeyDlgProofForAgency(connIda1,
          includeSendMsg = false, mockEntEdgeAgent1.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "missing required attribute: 'keyDlgProof'"))
        }
      }
    }

    "when sent CREATE_MSG (conn req answer) without replyToMsgId attribute" - {
      "should respond with bad request error msg" in {
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgWithoutReplyToMsgIdForAgency(connIda1,
          includeSendMsg = false, mockEntEdgeAgent1.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'replyToMsgId'"))
        }
      }
    }
  }

  def testAnswerSecondInvitation(): Unit = {
    createNewRelationship(connIda2)
    setInviteData(connIda2, mockEntEdgeAgent1, mockEntCloudAgent)

    "when sent CREATE_MSG (conn request answer) for second invite" - {
      "should respond with MSG_CREATED" in {
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda2,
          includeSendMsg = true, mockEntEdgeAgent1.pairwiseConnDetail(connIda2).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          mockConsumerEdgeAgent1.v_0_5_resp.handleInviteAnswerCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testRemoveConfigForConn(connIda1, Set(PUSH_COM_METHOD))

  }

  def testAcceptPreviousInvite(): Unit = {

    createNewRelationship(connIda3)

    "when sent CREATE_MSG (conn req answer) to accept same invite again" - {
      "should respond with error msg" in {
        val lastSentInvite = mockEntEdgeAgent1.pairwiseConnDetail(connIda2).lastSentInvite
        buildAgentPostReq(mockConsumerEdgeAgent1.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(
          connIda3, includeSendMsg = true, lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(PAIRWISE_KEYS_ALREADY_IN_WALLET.withMessage(
            s"pairwise keys already in wallet for did: ${lastSentInvite.senderDetail.agentKeyDlgProof.get.agentDID}"))
        }
      }
    }
  }

}
