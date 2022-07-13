package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants.PUSH_COM_METHOD
import com.evernym.verity.util2.Status.{INVALID_VALUE, MISSING_REQ_FIELD, MSG_STATUS_ACCEPTED, PAIRWISE_KEYS_ALREADY_IN_WALLET}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER}
import com.evernym.verity.http.base.open.{ExpectedMsgCriteria, ExpectedMsgDetail}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.testkit.mock.agent.MockEnv
import org.scalatest.time.{Seconds, Span}

trait ConnectionSpec { this : ConsumerEndpointHandlerSpec =>

  def testAnswerFirstInvitation(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val othersMockEdgeAgent = mockEnv.othersMockEnv.edgeAgent

    "provisions new relationship" - {
      createNewRelationship(mockEnv, connIda1)
      setInviteData(mockEnv.othersMockEnv.edgeAgent, mockEnv.othersMockEnv.cloudAgent, connIda1)
      testGetMsgsFromConnection(mockEdgeAgent, connIda1, ExpectedMsgCriteria(totalMsgs = 0))
    }

    testInvalidInviteAnswers(mockEnv)

    "when sent CREATE_MSG (conn req answer)" - {
      "should respond with success for create invite answer msg" in {
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda1, includeSendMsg = true,
          othersMockEdgeAgent.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          val resp = mockEdgeAgent.v_0_5_resp.handleInviteAnswerCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(totalMsgs = 2, List(
        ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ, MSG_STATUS_ACCEPTED),
        ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ_ANSWER, MSG_STATUS_ACCEPTED))
      )
    )

    testGetMsgsByConnections(mockEnv, 1, Map(connIda1 -> ExpectedMsgCriteria(2)))

  }

  private def testInvalidInviteAnswers(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val othersMockEdgeAgent = mockEnv.othersMockEnv.edgeAgent

    "when sent CREATE_MSG (conn req answer) with wrong agent key dlg proof provided" - {
      "should respond with bad request error msg" in {
        val msg = mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda1, includeSendMsg = false,
          buildInvalidRemoteAgentKeyDlgProof(othersMockEdgeAgent.pairwiseConnDetail(connIda1).lastSentInvite)).msg
        buildAgentPostReq(msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(INVALID_VALUE.withMessage(
            "com.evernym.vdrtools.InvalidStructureException: A value being processed is not valid."))
        }
      }
    }

    "when sent CREATE_MSG (conn req answer) with no key dlg proof" - {
      "should respond with bad request error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgWithoutKeyDlgProofForAgency(connIda1,
          includeSendMsg = false, othersMockEdgeAgent.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "missing required attribute: 'keyDlgProof'"))
        }
      }
    }

    "when sent CREATE_MSG (conn req answer) without replyToMsgId attribute" - {
      "should respond with bad request error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgWithoutReplyToMsgIdForAgency(connIda1,
          includeSendMsg = false, othersMockEdgeAgent.pairwiseConnDetail(connIda1).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'replyToMsgId'"))
        }
      }
    }
  }

  def testAnswerSecondInvitation(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val othersMockEdgeAgent = mockEnv.othersMockEnv.edgeAgent
    lazy val othersCloudAgent = mockEnv.othersMockEnv.cloudAgent

    createNewRelationship(mockEnv, connIda2)
    setInviteData(othersMockEdgeAgent, othersCloudAgent, connIda2)

    "when sent CREATE_MSG (conn request answer) for second invite" - {
      "should respond with MSG_CREATED" in {
        val totalAgentMsgsSentSoFar = getTotalAgentMsgsSentByCloudAgentToRemoteAgent
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(connIda2,
          includeSendMsg = true, othersMockEdgeAgent.pairwiseConnDetail(connIda2).lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe OK
          eventually (timeout(Span(5, Seconds))) {
            getTotalAgentMsgsSentByCloudAgentToRemoteAgent shouldBe totalAgentMsgsSentSoFar + 1
          }
          mockEdgeAgent.v_0_5_resp.handleInviteAnswerCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    testRemoveConfigForConn(mockEdgeAgent, connIda1, Set(PUSH_COM_METHOD))

  }

  def testAcceptPreviousInvite(mockEnv: MockEnv): Unit = {

    lazy val mockEdgeAgent = mockEnv.edgeAgent
    lazy val othersMockEdgeAgent = mockEnv.othersMockEnv.edgeAgent

    createNewRelationship(mockEnv, connIda3)

    "when sent CREATE_MSG (conn req answer) to accept same invite again" - {
      "should respond with error msg" in {
        val lastSentInvite = othersMockEdgeAgent.pairwiseConnDetail(connIda2).lastSentInvite
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAnswerInviteMsgForAgency(
          connIda3, includeSendMsg = true, lastSentInvite).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(PAIRWISE_KEYS_ALREADY_IN_WALLET.withMessage(
            s"pairwise keys already in wallet for did: ${lastSentInvite.senderDetail.agentKeyDlgProof.get.agentDID}"))
        }
      }
    }
  }

}
