package com.evernym.verity.actor.protocols

import akka.actor.Props
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Offer, Propose}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredentialProtoDef
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.{AcceptProposal, Sent}


class IssueCredProtocolActorSpec
  extends BaseProtocolActorSpec {

  //controller actor ids
  val CTRL_ID_1: String = CommonSpecUtil.generateNewDid().DID
  val CTRL_ID_2: String = CommonSpecUtil.generateNewDid().DID

  val credDefId = "1"
  val credValue = Map("name" -> "Alice")

  "Mock controller actors" - {
    "when sent SetupController command" - {
      "should get sup correctly" in {
        //platform
        sendToMockController(CTRL_ID_1,
          buildSetupController(CTRL_ID_1, Option(CTRL_ID_2), IssueCredentialProtoDef))
        expectMsg(Done)

        sendToMockController(CTRL_ID_2,
          buildSetupController(CTRL_ID_2, Option(CTRL_ID_1), IssueCredentialProtoDef))
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

  //overridden mapping to make the flow working (from actor protocol container to the 'mock controller')
  override lazy val mockRouteStoreActorTypeToRegions = Map(
    ACTOR_TYPE_USER_AGENT_ACTOR -> createRegion(MOCK_CONTROLLER_REGION_NAME, MockIssueCredControllerActor.props(appConfig))
  )

}

object MockIssueCredControllerActor {
  def props(ac: AppConfig): Props = Props(new MockIssueCredControllerActor(ac))
}

class MockIssueCredControllerActor(val appConfig: AppConfig) extends MockControllerActorBase