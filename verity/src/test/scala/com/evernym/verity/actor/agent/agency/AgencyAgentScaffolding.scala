package com.evernym.verity.actor.agent.agency

import akka.actor.PoisonPill
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.persistence.{ActorDetail, Done, GetActorDetail}
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.{AgencyPublicDid, EndpointSet}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.agency_admin.MockAgencyAdmin
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.testkit.mock.pushnotif.MockPushNotifListener
import com.evernym.verity.{ActorErrorResp, UrlDetail}
import org.scalatest.concurrent.Eventually


trait AgencyAgentScaffolding
  extends BasicSpec
    with PersistentActorSpec
    with AgentSpecHelper
    with MockPushNotifListener
    with Eventually {

  override lazy val mockAgencyAdmin: MockAgencyAdmin =
    new MockAgencyAdmin(system, UrlDetail("localhost:9001"), platform.agentActorContext.appConfig)

  override lazy val mockEdgeAgent: MockEdgeAgent = buildMockConsumerEdgeAgent(
    platform.agentActorContext.appConfig, mockAgencyAdmin)

  lazy val mockEdgeAgent1: MockEdgeAgent = buildMockConsumerEdgeAgent(
    platform.agentActorContext.appConfig, mockAgencyAdmin)

  protected def agencySetupSpecs(): Unit = {
    "when agency admin is interacting" - {

      "when sent GetLocalAgencyDIDDetail command before key creation" - {
        "should respond with agent not yet created error msg" in {
          aa ! GetLocalAgencyIdentity()
          expectError(AGENT_NOT_YET_CREATED.statusCode)
        }
      }

      "when sent CreateKey command" - {
        "should respond with created key detail" in {
          aa ! CreateKey(seed = Option("s" * 32))
          val dd = expectMsgType[AgencyPublicDid ]
          mockAgencyAdmin.handleFetchAgencyKey(dd)
          mockEdgeAgent.handleFetchAgencyKey(dd)
        }
      }

      "when sent CreateKey command a second time" - {
        "should respond with an error" taggedAs (UNSAFE_IgnoreLog) in {
          aa ! CreateKey(seed = Option("s" * 32))
          expectMsgType[ActorErrorResp].statusCode shouldBe FORBIDDEN.statusCode
        }
      }

      "when sent GetLocalAgencyDIDDetail command after agency key creation" - {
        "should respond with agency DID detail" in {
          aa ! GetLocalAgencyIdentity()
          val dd = expectMsgType[AgencyPublicDid]
          assert(mockAgencyAdmin.agencyPublicDid.contains(dd))
        }
      }

      "when sent SetEndpoint command" - {
        "should respond with EndpointSet msg" in {
          aa ! SetEndpoint
          expectMsgType[EndpointSet]
        }
      }

      "when sent SetEndpoint command again" - {
        "should respond with Forbidden error msg" in {
          aa ! SetEndpoint
          expectMsgType[ActorErrorResp].statusCode shouldBe FORBIDDEN.statusCode
        }
      }

      "when sent UpdateEndpoint command" - {
        "should respond with EndpointSet" in {
          aa ! UpdateEndpoint
          expectMsgType[EndpointSet]
        }
      }

      "when sent get-token msg" - {
        "should respond with token" in {
          val (r, pushNotifPayload) = withExpectNewPushNotif(validTestPushNotifToken, {
            val msg = mockEdgeAgent.v_0_1_req.prepareGetToken("id", "sponsorId", ComMethodDetail(1, validTestPushNotifToken))
            aa ! PackedMsgParam(msg, reqMsgContext)
            expectMsg(Done)
          })
        }
      }

    }
  }


  protected def restartSpecs(): Unit = {
    "when tried to restart actor" - {
      "should be successful and respond" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
        restartActor()
      }
    }
  }

  def restartActor(): Unit = {
    aa ! PoisonPill
    expectNoMessage()
    Thread.sleep(1000)
    aa ! GetActorDetail
    expectMsgType[ActorDetail]
  }
}
