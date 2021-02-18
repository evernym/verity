package com.evernym.verity.http.verity

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.Status._
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent._
import com.evernym.verity.actor.testkit.actor.MockSMSSender
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{CREATE_MSG_TYPE_CONN_REQ, CREATE_MSG_TYPE_CONN_REQ_ANSWER}
import com.evernym.verity.config.CommonConfig.HTTP_PORT
import com.evernym.verity.http.base.open.{ExpectedMsgCriteria, ExpectedMsgDetail}
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.texter.SMSSender
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.{MockEdgeAgent, MockEnv}

import scala.util.matching.Regex

trait ConnectionSpec { this: VerityEndpointHandlerSpec =>

  def testInvalidInvitations(mockEnv: MockEnv): Unit = {
    val mockEdgeAgent = mockEnv.edgeAgent

    "provisions new relationship" - {
      createNewRelationship(mockEnv, connIda1)
      testEnterpriseUpdateConfigs(mockEdgeAgent, connIda1)
      testGetMsgsFromConnection(mockEdgeAgent, connIda1, ExpectedMsgCriteria(totalMsgs = 0))
    }

    testInvitationWithInvalidPhoneNumber(mockEdgeAgent, connIda1)

    testExpiredInvitation(mockEdgeAgent, connIda1)

    testGetMsgsByConnections(mockEnv, 1, Map(
      connIda1 -> ExpectedMsgCriteria(List(ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ, MSG_STATUS_SENT)), Option(1))
    ))

  }

  private def testInvitationWithInvalidPhoneNumber(mockEdgeAgent: MockEdgeAgent, connId: String): Unit = {
    "when sent CREATE_MSG (connection request) with empty phone number" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateInviteMsgForAgency(
          connId, ph = Option("")).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(EMPTY_VALUE_FOR_OPTIONAL_FIELD.withMessage(
            s"empty value given for optional field: 'phoneNo'"))
        }
      }
    }

    "when sent CREATE_MSG (connection request) with invalid phone number" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateInviteMsgForAgency(connId,
          ph = Option("text"), includePublicDID = true).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(INVALID_VALUE.withMessage(
            "invalid phone number"))
        }
      }
    }
  }

  private def testExpiredInvitation(mockEdgeAgent: MockEdgeAgent, connId: String): Unit = {

    testSetInviteExpirationTime(mockEdgeAgent, connIda1, 0)

    "when sent CREATE_MSG (connection request)" - {
      "should respond with MSG_CREATED with invite detail" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateInviteMsgForAgency(connId,
          ph = Option(phoneNo)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          val isr = mockEdgeAgent.v_0_5_resp.handleInviteCreatedResp(PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connId))
          mockEdgeAgent.add(INVITE_URL, isr.md.urlToInviteDetail)
        }
      }
    }

    "when sent get invite detail api call" - {
      "should respond with expired invite" in {
        val url = s"/${getInviteUrl(mockEdgeAgent)}"
        buildGetReq(url) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MSG_VALIDATION_ERROR_EXPIRED.statusCode, "expired", None)
        }
      }
    }

    "when sent get invite detail with sms url for expired invite" - {
      "should respond with expired invite" taggedAs (UNSAFE_IgnoreLog) in {
        eventually {
          val inviteToken = getLastInviteUrlToken
          val inviteUrl = testMsgSendingSvc
            .mappedUrls(inviteToken)
            .replace("http://localhost:9000", "")

          buildGetReq(inviteUrl)  ~> epRoutes ~> check {
            status shouldBe BadRequest
            responseTo[StatusDetailResp] shouldBe StatusDetailResp(MSG_VALIDATION_ERROR_EXPIRED.statusCode, "expired", None)
          }
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(1, List(ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ, MSG_STATUS_SENT))))
  }

  def prepareForInviteAnswer(mockEnv: MockEnv): Unit = {
    "Consumer cloud agent" - {
      //TODO: need better wording here about what it is doing
      "when mimicing receiving connection request answer" - {
        "should be able to successfully setup required data" taggedAs (UNSAFE_IgnoreLog) in {
          addAgencyEndpointToLedger(mockEnv.cloudAgent.agencyAgentDetailReq.DID,
            mockEnv.cloudAgent.agencyEndpoint.toString)
          val le = mockEnv.edgeAgent.addNewLocalPairwiseKey(connIda1)
          val rec = mockEnv.cloudAgent.addNewLocalPairwiseKey(connIda1)
          le.setMyCloudAgentPairwiseDidPair(rec.myPairwiseDidPair.DID, rec.myPairwiseDidPair.verKey)
          rec.setTheirPairwiseDidPair(le.myPairwiseDidPair.DID, le.myPairwiseDidPair.verKey)
        }
      }
    }
  }

  def testReceiveAnswerWithoutInvitation(mockEnv: MockEnv): Unit = {
    "Ent Cloud agent (for client 1)" - {
      "when received CREATE_MSG (conn req answer) for expired invite" - {
        "should respond with error" in {
          buildAgentPostReq(mockEnv.othersMockEnv.cloudAgent.v_0_5_req.prepareInviteAnsweredMsgForAgency(
            connIda1, mockEnv.edgeAgent, mockEnv.othersMockEnv.edgeAgent).msg) ~> epRoutes ~> check {
            status shouldBe BadRequest
            responseTo[StatusDetailResp] shouldBe StatusDetailResp(MSG_VALIDATION_ERROR_EXPIRED)
          }
        }
      }
    }
  }

  def testSendConnectionRequest(mockEnv: MockEnv): Unit = {
    val mockEdgeAgent = mockEnv.edgeAgent

    testSetInviteExpirationTime(mockEdgeAgent, connIda1, 600)

    "when sent second CREATE_MSG (connection request)" - {
      "respond with MSG_CREATED with invite detail" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateInviteMsgForAgency(connIda1,
          ph = Option(phoneNo)).msg) ~> epRoutes ~> check {
          status shouldBe OK
          val isr = mockEdgeAgent.v_0_5_resp.handleInviteCreatedResp(
            PackedMsg(responseAs[Array[Byte]]), buildConnIdMap(connIda1))
          mockEdgeAgent.add(INVITE_URL, isr.md.urlToInviteDetail)
        }
      }
    }

    "when sent get invite detail request after second connection request" - {
      "should respond success" in {
        buildGetReq(s"/${getInviteUrl(mockEdgeAgent)}") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    "when sent get invite detail with sms url for valid invite" - {
      "should respond with expired invite" in {
        eventually {
          val inviteToken = getLastInviteUrlToken
          val inviteUrl = testMsgSendingSvc
            .mappedUrls(inviteToken)
            .replace("http://localhost:9000", "")

          buildGetReq(inviteUrl)  ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }
    }

    testGetMsgsFromConnection(mockEdgeAgent, connIda1, ExpectedMsgCriteria(totalMsgs = 2))

    testGetMsgsByConnections(mockEnv, 1, Map(connIda1 -> ExpectedMsgCriteria(totalMsgs = 2)))


    "when received CREATE_MSG (conn req answer) for valid invite" - {
      "should respond with success" in {
        buildAgentPostReq(mockEnv.othersMockEnv.cloudAgent.v_0_5_req.prepareInviteAnsweredMsgForAgency(
          connIda1, mockEdgeAgent, mockEnv.othersMockEnv.edgeAgent).msg) ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    testGetMsgsFromConnection(
      mockEdgeAgent,
      connIda1,
      ExpectedMsgCriteria(totalMsgs = 3, List(
        ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ, MSG_STATUS_ACCEPTED),
        ExpectedMsgDetail(CREATE_MSG_TYPE_CONN_REQ_ANSWER, MSG_STATUS_ACCEPTED))
      )
    )

    testGetMsgsByConnections(mockEnv, 1, Map(connIda1 -> ExpectedMsgCriteria(3)))

    "when sent CREATE_MSG (connection request) when one connection request is already accepted" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateInviteMsgForAgency(connIda1,
          ph = Option(phoneNo)).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(ACCEPTED_CONN_REQ_EXISTS)
        }
      }
    }

    testRemoveConfigForConn(mockEdgeAgent, connIda1, Set("msgs.conn-req-expiration-time-in-seconds"))

  }

  lazy val httpPort: Int = appConfig.getConfigIntReq(HTTP_PORT)
  lazy val INVITE_URL_REG_EX: Regex = s"""$inviteSenderName would like you to install Connect-Me for greater identity verification: https:\\/\\/connectme.app.link\\?t=(.*)""".r
  lazy val smsSender: SMSSender = platform.agentActorContext.smsSvc

  def getLastInviteUrlToken: String = {
    val url = smsSender.asInstanceOf[MockSMSSender].phoneTexts.last._2
    url match {
      case INVITE_URL_REG_EX(token)   =>
        token
      case _ => throw new InvalidValueException(Option("invalid invite url: " + url))
    }
  }
}
