package com.evernym.verity.actor.protocols

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.actor.testkit.{CommonSpecUtil, PersistentActorSpec}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.protocol.engine.PinstIdPair
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Offer, Propose}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredentialProtoDef
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.{AcceptProposal, Sent}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

import scala.reflect.ClassTag

class IssueCredProtocolActorSpec
  extends PersistentActorSpec
    with BasicSpec
    with ShardUtil {

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"akka.actor.serialize-messages = off"
    }
  }

  //controller actor ids
  val CTRL_ID_1: String = CommonSpecUtil.generateNewDid().DID
  val CTRL_ID_2: String = CommonSpecUtil.generateNewDid().DID

  val credDefId = "1"
  val credValue = Map("name" -> "Alice")

  "Mock controller actors" - {
    "when sent SetupController command" - {
      "should get sup correctly" in {
        platform
        sendToMockController(CTRL_ID_1,
          ControllerData(CTRL_ID_1, Option(CTRL_ID_2), agentActorContext, PinstIdPair(CTRL_ID_1, IssueCredentialProtoDef)))
        expectMsg(Done)

        sendToMockController(CTRL_ID_2,
          ControllerData(CTRL_ID_2, Option(CTRL_ID_1), agentActorContext, PinstIdPair(CTRL_ID_2, IssueCredentialProtoDef)))
        expectMsg(Done)
      }
    }
  }

  "Controller 1" - {
    s"when sent 'Propose' message" - {
      "should be processed successfully" in {
        sendToMockController(CTRL_ID_1, SendControlMsg(Propose(credDefId, credValue)))
        expectMsgTypeFrom[Sent](CTRL_ID_1)
      }
    }
  }

  "Controller 2" - {
    "should receive Signal message about accepting the proposal" in {
      expectMsgTypeFrom[AcceptProposal](CTRL_ID_2)
    }

    "when sent Offer" - {
      "should receive Signal message about sending the offer" in {
        sendToMockController(CTRL_ID_2, SendControlMsg(Offer(credDefId, credValue)))
        expectMsgTypeFrom[Sent](CTRL_ID_2)
      }
    }
  }

  def expectMsgTypeFrom[T](id: String)(implicit t: ClassTag[T]): T = {
    val m = expectMsgType[T]
    assert(lastSender.toString().contains(id), s"msg received from different controller")
    m
  }

  val MOCK_CONTROLLER_REGION_NAME = "MockIssueCredControllerActor"

  lazy val mockControllerRegion: ActorRef = {
    ClusterSharding(system).shardRegion(MOCK_CONTROLLER_REGION_NAME)
  }

  def sendToMockController(id: String, cmd: Any): Unit = {
    mockControllerRegion ! ForIdentifier(id, cmd)
  }

  //overridden mapping to make the flow working (from actor protocol container to the 'mock controller')
  override lazy val mockRouteStoreActorTypeToRegions = Map(
    ACTOR_TYPE_USER_AGENT_ACTOR -> createRegion(MOCK_CONTROLLER_REGION_NAME, MockIssueCredControllerActor.props(appConfig))
  )
}

object MockIssueCredControllerActor {
  def props(ac: AppConfig): Props = Props(new MockIssueCredControllerActor(ac))
}

class MockIssueCredControllerActor(val appConfig: AppConfig) extends MockControllerActorBase