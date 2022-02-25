package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util2.Status.{INVALID_VALUE, MISSING_REQ_FIELD}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.msgfamily.configs.ComMethodPackaging
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.testkit.util.TestComMethod
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.MockEnv

trait UpdateComMethodSpec { this : EdgeEndpointBaseSpec =>

  def testUpdateComMethod(mockEnv: MockEnv): Unit = {
    testInvalidUpdateComMethod(mockEnv)
    testValidUpdateComMethod(mockEnv)
  }

  def testInvalidUpdateComMethod(mockEnv: MockEnv): Unit = {
    val mockEdgeAgent = mockEnv.edgeAgent

    "when sent UPDATE_COM_METHOD msg with blank value" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(TestComMethod("1", COM_METHOD_TYPE_PUSH,
          Option(""))).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'value'"))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg with invalid protocol" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(TestComMethod("1", COM_METHOD_TYPE_HTTP_ENDPOINT,
          Option("ws:/abc"))).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(INVALID_VALUE.withMessage(
            "invalid http endpoint: 'ws:/abc' reason: unknown protocol: ws"))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg with missing value" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(TestComMethod("1",
          COM_METHOD_TYPE_PUSH, None)).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'value'"))
        }
      }
    }

    "when sent UPDATE_COM_METHOD with invalid value" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(TestComMethod("1", COM_METHOD_TYPE_PUSH,
          Option("test"))).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(INVALID_VALUE.withMessage(
            "push address 'test' must match this regular expression: (FCM):(.*)"))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) indy pack without recipientKeys" - {
      "should respond with error msg" in {
        val tp = ComMethodPackaging("1.0", None)
        val cm = TestComMethod("2", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://example.com/123"), Option(tp))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'recipientKeys'")
          )
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) packed with empty recipientKeys" - {
      "should respond with error msg" taggedAs (UNSAFE_IgnoreLog) in {
        val tp = ComMethodPackaging("1.0", Option(Set()))
        val cm = TestComMethod("2", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://example.com/123"), Option(tp))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'recipientKeys'")
          )
        }
      }
    }
  }

  def testValidUpdateComMethod(mockEnv: MockEnv): Unit = {
    val mockEdgeAgent = mockEnv.edgeAgent

    "when sent UPDATE_COM_METHOD msg" - {
      "should respond with success" in {
        val cm = TestComMethod("1", COM_METHOD_TYPE_PUSH, Option(validTestPushNotifToken))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleComMethodUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) without specifying packaging options (legacy)" - {
      "should respond with success" in {
        val cm = TestComMethod("2", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://example.com/123"), None)
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleComMethodUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) packed with 1 recipient key" - {
      "should respond with success" in {
        val tp = ComMethodPackaging("1.0", Option(Set(mockEdgeAgent.myDIDDetail.verKey)))
        val cm = TestComMethod("2", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://example.com/123"), Option(tp))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleComMethodUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) packed with multiple recipient keys" - {
      "should respond with success" taggedAs (UNSAFE_IgnoreLog) in {
        val tp = ComMethodPackaging("1.0", Option(Set(mockEdgeAgent.myDIDDetail.verKey,
          mockEnv.cloudAgent.myDIDDetail.verKey)))
        val cm = TestComMethod("2", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://example.com/123"), Option(tp))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleComMethodUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent UPDATE_COM_METHOD msg (http) plain" - {
      "should respond with success" in {
        val tp = ComMethodPackaging("plain", None)
        val cm = TestComMethod("3", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("http://rest.url/123"), Option(tp))
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateComMethodMsgForAgency(cm).msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleComMethodUpdatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }
  }
}
