package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants.LOGO_URL_KEY
import com.evernym.verity.util2.Status.{AGENT_ALREADY_CREATED, AGENT_NOT_YET_CREATED, ALREADY_REGISTERED, CONN_STATUS_ALREADY_CONNECTED, INVALID_VALUE, MISSING_REQ_FIELD, NOT_REGISTERED, UNSUPPORTED_MSG_TYPE}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.testkit.util.TestConfigDetail
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.testkit.mock.agent.MockEnv


trait AgentProvisioningSpec { this : EdgeEndpointBaseSpec =>

  def testAgentProvisioning(mockEnv: MockEnv): Unit = {
    lazy val mockEdgeAgent = mockEnv.edgeAgent

    s"when sent get agency key api" - {
      "should respond with agency did/key detail" taggedAs UNSAFE_IgnoreLog in {
        buildGetReq(s"/agency") ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.handleFetchAgencyKey(responseTo[AgencyPublicDid])
        }
      }
    }

    s"when sent agent msg with some garbage data " - {
      "should respond with proper error msg" in {
        buildAgentPostReq("garbage".getBytes) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(INVALID_VALUE.statusCode, "invalid sealed/encrypted box: Error: Invalid structure\n  Caused by: Unable to open sodium sealedbox\n", None)
        }
      }
    }

    var cm = emptyPackedMsgWrapper
    "when sent SIGNUP msg before connected with agency" - {
      "should respond with agent not created error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareSignUpMsgBeforeConnectedForAgency.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(AGENT_NOT_YET_CREATED.withMessage(
            s"agent not created for route: ${mockEdgeAgent.myDIDDetail.did}"))
        }
      }
    }

    "when sent CONNECT msg without type name" - {
      "should respond with error" taggedAs (UNSAFE_IgnoreLog) in {
        cm = mockEdgeAgent.v_0_5_req.prepareConnectMsgWithMissingTypeFieldForAgency
        buildAgentPostReq(cm.msg) ~> epRoutes ~> check {
          status shouldBe NotFound
          responseTo[StatusDetailResp].statusCode shouldBe UNSUPPORTED_MSG_TYPE.statusCode
        }
      }
    }

    "when sent CONNECT msg with invalid sealed box" - {
      "should respond with error" in {
        cm = mockEdgeAgent.v_0_5_req.prepareInvalidPackedConnectMsgForAgency
        buildAgentPostReq(cm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp].statusCode shouldBe INVALID_VALUE.statusCode
        }
      }
    }

    "when sent CONNECT msg with wrong ver key" - {
      "should respond with error to a CONNECT msg with wrong verKey" taggedAs (UNSAFE_IgnoreLog) in {
        val newID = generateNewAgentDIDDetail()
        cm = mockEdgeAgent.v_0_5_req.prepareConnectMsgWithWrongVerKeyForAgency(newID.verKey)
        buildAgentPostReq(cm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(
            INVALID_VALUE.withMessage(s"DID and verKey not belonging to each other (DID: ${mockEdgeAgent.myDIDDetail.did}, verKey: ${newID.verKey})"))
        }
      }
    }

    "when sent CONNECT msg" - {
      "should respond with CONNECTED msg" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        cm = mockEdgeAgent.v_0_5_req.prepareConnectMsgForAgency
        buildAgentPostReq(cm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleConnectedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent CONNECT msg again" - {
      "should respond with already connected error msg" in {
        buildAgentPostReq(cm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(CONN_STATUS_ALREADY_CONNECTED)
        }
      }
    }

    var sm = emptyPackedMsgWrapper
    "when sent CREATE_AGENT msg before SIGNUP" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateAgentMsgBeforeRegisteredForAgency.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(NOT_REGISTERED)
        }
      }
    }

    "when sent SIGNUP msg" - {
      "should respond with SIGNED_UP msg" in {
        sm = mockEdgeAgent.v_0_5_req.prepareSignUpMsgForAgency
        buildAgentPostReq(sm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleSignedUpResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent SIGNUP msg again" - {
      "should respond with already signed up" in {
        buildAgentPostReq(sm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(ALREADY_REGISTERED)
        }
      }
    }

    var cam = emptyPackedMsgWrapper
    "when sent CREATE_KEY before agent created" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareCreateKeyMsgBeforeAgentCreatedForAgency.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(AGENT_NOT_YET_CREATED.withMessage(
            s"agent not created for route: ${mockEdgeAgent.myDIDDetail.did}"))
        }
      }
    }

    "when sent CREATE_AGENT msg" - {
      "should respond AGENT_CREATED msg" taggedAs (UNSAFE_IgnoreLog) in {
        cam = mockEdgeAgent.v_0_5_req.prepareCreateAgentMsgForAgency
        buildAgentPostReq(cam.msg) ~> epRoutes ~> check {
          status shouldBe OK
          mockEdgeAgent.v_0_5_resp.handleAgentCreatedResp(PackedMsg(responseAs[Array[Byte]]))
        }
      }
    }

    "when sent CREATE_AGENT msg again" - {
      "should respond agent already created error msg" in {
        buildAgentPostReq(cam.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(AGENT_ALREADY_CREATED)
        }
      }
    }

    "when sent UPDATE_CONFIGS with missing value" - {
      "should respond with error msg" in {
        buildAgentPostReq(mockEdgeAgent.v_0_5_req.prepareUpdateConfigsForAgentMsgForAgency(
          Set(TestConfigDetail(LOGO_URL_KEY))).msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseTo[StatusDetailResp] shouldBe StatusDetailResp(MISSING_REQ_FIELD.withMessage(
            "required attribute not found (missing/empty/null): 'value'"))
        }
      }
    }
  }
}
