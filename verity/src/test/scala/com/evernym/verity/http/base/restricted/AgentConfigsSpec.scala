package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.testkit.util.TestConfigDetail
import com.evernym.verity.actor.wallet.PackedMsg

trait AgentConfigsSpec { this : EndpointHandlerBaseSpec =>

  def mockEdgeAgent: MockEdgeAgent
  def inviteSenderName: String
  def inviteSenderLogoUrl: String

  def testEnterpriseUpdateConfigs(connId: String): Unit = {

    "when sent UPDATE_CONFIGS msg with unsupported version of FWD msg" - {
      "should respond with unsupported version error msg" in {
        val configs = Set(TestConfigDetail("verity.msgs.conn-req-expiration-time-in-seconds", Option("0")))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateConfigsForConnMsgForAgency("1.1", connId, configs).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp].statusCode shouldBe UNSUPPORTED_MSG_TYPE.statusCode
        }
      }
    }

    "when sent UPDATE_CONFIGS msg with name and logo urls" - {
      "should respond with CONFIGS_UPDATED msg" in {
        val configs = Set(TestConfigDetail(NAME_KEY, Option(inviteSenderName)), TestConfigDetail(LOGO_URL_KEY, Option(inviteSenderLogoUrl)))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateConfigsForAgentMsgForAgency(configs).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleConfigsUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent GET_CONFIGS msg" - {
      "should respond with CONFIGS msg" in {
        val getConfigs = Set(NAME_KEY, LOGO_URL_KEY)
        val expConfigs = Set(ConfigDetail(NAME_KEY, inviteSenderName), ConfigDetail(LOGO_URL_KEY, inviteSenderLogoUrl))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareGetAgentConfigsMsgForAgency(getConfigs).msg) ~> epRoutes ~> check {
          status shouldBe OK
          val gcr = mockEdgeAgent.v_0_5_resp.handleGetConfigsResp(PackedMsg(responseAs[Array[Byte]]))
          expConfigs.foreach { ec =>
            gcr.configs.exists(gc => gc.name == ec.name && gc.value.contains(ec.value)) shouldBe true
          }
        }
      }
    }
  }

  def testSetInviteExpirationTime(connId: String, expireInSeconds: Int): Unit = {
    s"when sent UPDATE_CONFIGS msg to $connId invite msg expiry time to $expireInSeconds seconds" - {
      "should respond with CONFIGS_UPDATED msg" in {
        val configs = Set(TestConfigDetail("verity.msgs.conn-req-expiration-time-in-seconds", Option(expireInSeconds.toString)))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateConfigsForConnMsgForAgency(connId, configs).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleConfigsUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }
  }

  def testRemoveConfigForConn(connId: String, configs: Set[String]): Unit = {
    "when sent REMOVE_CONFIGS msg" - {
      "should response with CONFIGS_REMOVED msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareRemoveConfigsMsgForConnForAgency(connId, configs).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleConfigsRemovedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }
  }
}
