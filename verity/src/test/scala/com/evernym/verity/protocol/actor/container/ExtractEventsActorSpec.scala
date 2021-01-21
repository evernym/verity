package com.evernym.verity.protocol.actor.container

import akka.actor.Props
import akka.testkit.EventFilter
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.{DidDoc, Relationship}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.protocol.actor._
import com.evernym.verity.protocol.actor.container.base.{BaseProtocolActorSpec, MockControllerActorBase, SendActorMsg, SendControlMsg}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Propose
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredentialProtoDef
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.Sent

import scala.concurrent.duration._
import scala.language.postfixOps

class ExtractEventsActorSpec
  extends BaseProtocolActorSpec {

    val credDefId = "1"
    val credValue = Map("name" -> "Alice")

    "ExtractEventsActor" - {

      "empty event stream should return ExtractionComplete imminently" in {
        system.actorOf(ExtractEventsActor.prop(appConfig, "test", "test", testActor))
        expectMsgPF() {
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
      }

      "empty event stream should stop after sending ExtractionComplete" in {
        system.actorOf(ExtractEventsActor.prop(appConfig, "test", "test", testActor))
        expectMsgPF() {
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
        EventFilter.debug(start = "in post stop") assertDone(1 second)
      }

      "extract events actor should extract all events from protocol actor" in {
        val CTRL_ID_1: String = CommonSpecUtil.generateNewDid().DID
        val CTRL_ID_2: String = CommonSpecUtil.generateNewDid().DID     //domain 2 controller
        sendToMockController(CTRL_ID_1,
          buildSetupController(CTRL_ID_1, Option(CTRL_ID_2), IssueCredentialProtoDef))
        expectMsg(Done)

        sendToMockController(CTRL_ID_1, SendControlMsg(Propose(credDefId, credValue)))
        expectMsgTypeFrom[Sent](CTRL_ID_1)

        system.actorOf(ExtractEventsActor.prop(
          appConfig,
          "issue-credential-1.0-protocol",
          CTRL_ID_1,
          testActor)
        )
        val events = receiveWhile(5 seconds, .25 seconds) {
          case ProtocolCmd(e: ExtractedEvent, None)     => e
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
        events.last shouldBe an[ExtractionComplete]
        events.slice(0, events.size - 1).foreach(_ shouldBe an[ExtractedEvent])
      }

      "single event stream should return the single event" in {
        val CTRL_ID_1: String = CommonSpecUtil.generateNewDid().DID
        val CTRL_ID_2: String = CommonSpecUtil.generateNewDid().DID     //domain 2 controller
        val CTRL_ID_OTHER: String = CommonSpecUtil.generateNewDid().DID


        // Start first protocol
        sendToMockController(
          CTRL_ID_1,
          buildSetupController(
            CTRL_ID_1,
            Option(CTRL_ID_OTHER),
            IssueCredentialProtoDef)
        )
        expectMsg(Done)

        sendToMockController(CTRL_ID_1, SendControlMsg(Propose(credDefId, credValue)))
        expectMsgTypeFrom[Sent](CTRL_ID_1)

        // Start second protocol that will copy the first protocol
        sendToMockController(
          CTRL_ID_2,
          buildSetupController(
            CTRL_ID_2,
            Option(CTRL_ID_OTHER),
            IssueCredentialProtoDef
          )
        )
        expectMsg(Done)


        val mockRel = Relationship(
          PAIRWISE_RELATIONSHIP,
          "mockRel1",
          Some(DidDoc(CTRL_ID_2)),
          Seq(DidDoc(CTRL_ID_OTHER)),
        )
        sendToMockController(CTRL_ID_2, SendActorMsg(FromProtocol(CTRL_ID_1, mockRel)))
        Thread.sleep(1000)

        system.actorOf(ExtractEventsActor.prop(
          appConfig,
          "issue-credential-1.0-protocol",
          CTRL_ID_2,
          testActor)
        )
        val events = receiveWhile(5 seconds, .25 seconds) {
          case ProtocolCmd(e: ExtractedEvent, None)     => e
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
        events.last shouldBe an[ExtractionComplete]
        events.slice(0, events.size - 1).foreach(_ shouldBe an[ExtractedEvent])
      }
    }

  //overriding agent msg routing mapping to make the flow working
  // (from actor protocol container to the 'mock controller')
  override lazy val mockRouteStoreActorTypeToRegions = Map(
    ACTOR_TYPE_USER_AGENT_ACTOR -> createRegion(MOCK_CONTROLLER_REGION_NAME, MockIssueCredControllerActor.props(appConfig))
  )
}

object MockIssueCredControllerActor {
  def props(ac: AppConfig): Props = Props(new MockIssueCredControllerActor(ac))
}

class MockIssueCredControllerActor(val appConfig: AppConfig) extends MockControllerActorBase