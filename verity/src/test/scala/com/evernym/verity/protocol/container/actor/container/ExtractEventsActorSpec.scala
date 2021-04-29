package com.evernym.verity.protocol.container.actor.container

import akka.actor.Props
import akka.testkit.EventFilter
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.{DidDoc, Relationship}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.WithAdditionalLogs
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.protocol.container.actor._
import com.evernym.verity.protocol.container.actor.container.base.{BaseProtocolActorSpec, MockControllerActorBase, SendControlMsg, SendToProtocolActor}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Propose
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredentialProtoDef
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.Sent
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.language.postfixOps

class ExtractEventsActorSpec
  extends BaseProtocolActorSpec
    with WithAdditionalLogs {

    val credDefId = "1"
    val credValue = Map("name" -> "Alice")

  "ExtractEventsActor" - {

    "empty event stream should return ExtractionComplete imminently" in {
      EventFilter.debug(pattern = ".*in post stop", occurrences = 1) intercept {
        ConfigUtil.getDataRetentionPolicy(appConfig, "", "")
        system.actorOf(ExtractEventsActor.prop(appConfig, "test", "test", testActor))
        expectMsgPF() {
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
      }
    }

    "empty event stream should stop after sending ExtractionComplete" in {
      EventFilter.debug(pattern = ".*in post stop", occurrences = 1) intercept {
        system.actorOf(ExtractEventsActor.prop(appConfig, "test", "test", testActor))
        expectMsgPF() {
          case ProtocolCmd(e: ExtractionComplete, None) => e
        }
      }
    }

    "extract events actor should extract all events from protocol actor" in {
      val CTRL_ID_1: String = generateNewDid().DID
      val CTRL_ID_2: String = generateNewDid().DID

      val mockController1 = buildMockController(IssueCredentialProtoDef, CTRL_ID_1, CTRL_ID_2) //domain 1 controller

      mockController1.startSetup()
      mockController1.expectMsg(Done)

      mockController1.sendCmd(SendControlMsg(Propose(credDefId, credValue)))
      mockController1.expectMsgType[Sent]

      system.actorOf(ExtractEventsActor.prop(
        appConfig,
        "issue-credential-1.0-protocol",
        mockController1.myDID,
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
      val CTRL_ID_1: String = generateNewDid().DID
      val CTRL_ID_2: String = generateNewDid().DID
      val CTRL_ID_OTHER: String = generateNewDid().DID

      val mockController1 = buildMockController(IssueCredentialProtoDef, CTRL_ID_1, CTRL_ID_OTHER) //domain 1 controller
      val mockController2 = buildMockController(IssueCredentialProtoDef, CTRL_ID_2, CTRL_ID_OTHER) //domain 2 controller

      // Start first protocol
      mockController1.startSetup()
      mockController1.expectMsg(Done)

      mockController1.sendCmd(SendControlMsg(Propose(credDefId, credValue)))
      mockController1.expectMsgType[Sent]

      // Start second protocol that will copy the first protocol
      mockController2.startSetup()
      mockController2.expectMsg(Done)

      val mockRel = Relationship(
        PAIRWISE_RELATIONSHIP,
        "mockRel1",
        Some(DidDoc(mockController2.myDID)),
        Seq(DidDoc(mockController1.theirDID)),
      )
      mockController2.sendCmd(SendToProtocolActor(FromProtocol(mockController1.myDID, mockRel)))
      Thread.sleep(1000)

      system.actorOf(ExtractEventsActor.prop(
        appConfig,
        "issue-credential-1.0-protocol",
        mockController2.myDID,
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

  override lazy val mockControllerActorProps: Props = MockIssueCredControllerActor.props(appConfig, agentActorContext)

  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      "akka.loglevel = DEBUG"
    }
  }
}

object MockIssueCredControllerActor {
  def props(ac: AppConfig, aac: AgentActorContext): Props = Props(new MockIssueCredControllerActor(ac, aac))
}

class MockIssueCredControllerActor(appConfig: AppConfig, aac: AgentActorContext)
  extends MockControllerActorBase(appConfig, aac)
