package com.evernym.verity.actor.persistence.recovery.latest.verity2.vas

import akka.actor.{Actor, ActorRef, Props}
import com.evernym.verity.actor.SignedUp
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.actor.appStateManager.AppStateEvent
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.GetPersistentActorDetail
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class UnhandledEventRecoverySpec
  extends BaseRecoveryActorSpec
    with AgencyAgentEventSetter
    with UserAgentEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    addEventsToPersistentStorage(mySelfRelAgentPersistenceId, scala.collection.immutable.Seq(SignedUp()))
  }

  "User agent actor" - {
    "when started with an unhandled event" - {
      "should not change app state" in {
        system.actorOf(MockAppStateListener.props(self))
        uaRegion ! GetPersistentActorDetail
        expectNoMessage(5.seconds)
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}

object MockAppStateListener {
  def props(replyTo: ActorRef): Props = Props(new MockAppStateListener(replyTo))
}

class MockAppStateListener(replyTo: ActorRef)
  extends Actor {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[AppStateEvent])
  }

  override def receive: Receive = {
    case ase: AppStateEvent => replyTo ! ase
  }
}