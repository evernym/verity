package com.evernym.verity.actor.agent.user

import akka.actor.PoisonPill
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.SetupAgentEndpoint
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.persistence.{ActorDetail, Done, GetActorDetail}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.common.AgentWalletSetupProvider
import com.evernym.verity.push_notification.MockPusher
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.testkit.util.TestComMethod
import com.evernym.verity.UrlDetail
import org.scalatest.concurrent.Eventually


trait UserAgentSpecScaffolding
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with AgentWalletSetupProvider
    with Eventually {

  implicit def msgPackagingContext: AgentMsgPackagingContext

  override lazy val mockAgencyAdmin: MockAgencyAdmin =
    new MockAgencyAdmin(system, UrlDetail("localhost:9001"), platform.agentActorContext.appConfig)

  override lazy val mockEdgeAgent: MockEdgeAgent = buildMockConsumerEdgeAgent(
    platform.agentActorContext.appConfig, mockAgencyAdmin)

  import mockEdgeAgent._
  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  def alltests(ua: agentRegion, userDID: DID, userDIDVerKey: VerKey)

  def setupUserAgentSpecs(ua: agentRegion, userDID: DID, userDIDVerKey: VerKey): Unit = {
    "when sent GetConfigs msg" - {
      "should respond with Configs msg with empty list" in {
        ua ! GetConfigs(Set(""))
        expectMsg(AgentConfigs(Set.empty))
      }
    }

    "when sent InitReq command" - {
      "should create/initialize agent actor" in {
        val agentPairwiseKey = prepareNewAgentWalletData(userDID, userDIDVerKey, userAgentEntityId)
        ua ! SetupAgentEndpoint(userDID, agentPairwiseKey.did)
        expectMsg(Done)
        mockEdgeAgent.handleAgentCreatedRespForAgent(agentPairwiseKey.did, agentPairwiseKey.verKey)
      }
    }
  }

  def userAgentBaseSpecs(): Unit = {

    "User Agent" - {
      //fixture for common user agent used across tests in this scope
      lazy val ua: agentRegion = agentRegion(userAgentEntityId, userAgentRegionActor)

      lazy val userDID: DID = mockEdgeAgent.myDIDDetail.did
      lazy val userDIDVerKey: VerKey = mockEdgeAgent.getVerKeyFromWallet(userDID)

      setupUserAgentSpecs(ua, userDID, userDIDVerKey)
      updateComMethodSpecs()
      alltests(ua, userDID, userDIDVerKey)
      restartSpecs()
    }
  }

  def updateComMethodSpecs(): Unit = {
    "when sent UPDATE_COM_METHOD msg with unsupported version" - {
      "should respond with unsupported version error msg" in {
        val pcm = TestComMethod ("1", COM_METHOD_TYPE_PUSH, Option(s"${MockPusher.comMethodPrefix}:112233"))
        val msg = prepareUpdateComMethodMsgForAgentBase(unsupportedVersion, pcm)
        ua ! wrapAsPackedMsgParam(msg)
        expectError(UNSUPPORTED_MSG_TYPE.statusCode)    //TODO: message version not supported is not checked
      }
    }

    "when sent UPDATE_COM_METHOD msg" - {
      "should respond with COM_METHOD_UPDATED msg" in {
        val pcm = TestComMethod ("1", COM_METHOD_TYPE_PUSH, Option(s"${MockPusher.comMethodPrefix}:112233"))
        val msg = prepareUpdateComMethodMsgForAgent(pcm)
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        handleComMethodUpdatedResp(pm)
      }
    }

    //TODO duplicate of above, but for 0_6... need to reuse
    "when sent UPDATE_COM_METHOD 0.6 msg" - {
      "should respond with COM_METHOD_UPDATED 0.6 msg" in {
        val pcm = TestComMethod ("1", COM_METHOD_TYPE_PUSH, Option(s"${MockPusher.comMethodPrefix}:112233"))
        val msg = mockEdgeAgent.v_0_6_req.prepareUpdateComMethodMsgForAgent(pcm)
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        mockEdgeAgent.v_0_6_resp.handleComMethodUpdatedResp(pm)
      }
    }

    "when sent UPDATE_COM_METHOD 1.0 msg" - {
      "should respond with COM_METHOD_UPDATED 1.0 msg" in {
        val pcm = TestComMethod ("1", COM_METHOD_TYPE_PUSH, Option(s"${MockPusher.comMethodPrefix}:112233"))
        val msg = mockEdgeAgent.v_1_0_req.prepareUpdateComMethodMsgForAgent(pcm)
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]

        //TODO (why request is in 1.0, but response checking is with 0.6)
        mockEdgeAgent.v_0_6_resp.handleComMethodUpdatedResp(pm)
      }
    }

    "when sent UPDATE_COM_METHOD msg to register an invalid endpoint" - {
      "should respond with COM_METHOD_UPDATED msg" in {
        val hcm = TestComMethod ("1", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("localhost"))
        val msg = prepareUpdateComMethodMsgForAgent(hcm)
        ua ! wrapAsPackedMsgParam(msg)
        expectError(INVALID_VALUE.statusCode)
      }
    }

    "when sent UPDATE_COM_METHOD msg to register a valid endpoint" - {
      "should respond with COM_METHOD_UPDATED msg" in {
        val hcm = TestComMethod ("1", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("https://my.domain.com/xyz"))
        val msg = prepareUpdateComMethodMsgForAgent(hcm)
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        handleComMethodUpdatedResp(pm)
      }
    }

    "when sent UPDATE_COM_METHOD msg to register a different endpoint" - {
      "should respond with COM_METHOD_UPDATED msg" in {
        val hcm = TestComMethod ("1", COM_METHOD_TYPE_HTTP_ENDPOINT, Option("localhost:7000"))
        val msg = prepareUpdateComMethodMsgForAgent(hcm)
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        handleComMethodUpdatedResp(pm)
      }
    }

    s"when sent GET_MSGS msg " - {
      "should response with MSGS" in {
        val msg = prepareGetMsgs()
        ua ! wrapAsPackedMsgParam(msg)
        val pm = expectMsgType[PackedMsg]
        handleGetMsgsResp(pm)
      }
    }
  }

  protected def restartSpecs(): Unit = {
    "when tried to restart actor" - {
      "should be successful and respond" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
        ua ! PoisonPill
        expectNoMessage()
        Thread.sleep(1000)
        aa ! GetActorDetail
        expectMsgType[ActorDetail]
      }
    }
  }
}
