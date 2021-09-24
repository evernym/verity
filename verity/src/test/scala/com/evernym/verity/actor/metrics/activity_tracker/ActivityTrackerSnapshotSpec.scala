package com.evernym.verity.actor.metrics.activity_tracker

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, StoreRoute}
import com.evernym.verity.actor.{ForIdentifier, ShardUtil}
import com.evernym.verity.actor.agent.{ActivityState, AgentParam, HasAgentActivity, SponsorRel}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.actor.{OverrideConfig, SnapshotSpecBase}
import com.evernym.verity.constants.ActorNameConstants.ACTIVITY_TRACKER_REGION_ACTOR_NAME
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

class ActivityTrackerSnapshotSpec
  extends BasicSpec
    with PersistentActorSpec
    with SnapshotSpecBase
    with HasAgentActivity
    with OverrideConfig
    with ShardUtil
    with Eventually {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = platform.activityTrackerRegion
    sendMsgToAgentRouteStore(domainId, StoreRoute(ActorAddressDetail(DUMMY_ACTOR_TYPE_ID, actorEntityId)))
  }

  "ActivityTracker" - {
    "when sent activity commands" - {
      "should store corresponding events" in {
        AgentActivityTracker.track("msg-type", domainId)
        AgentActivityTracker.track("msg-type", domainId)
        AgentActivityTracker.track("msg-type", domainId)
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          totalSnapshots shouldBe 1
        }
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          totalEvents shouldBe 1
        }
        val snapshot = latestSnapshot.value
        snapshot.agentParam shouldBe Option(AgentParam(domainId, Option(SponsorRel("sponsor-id", "sponsee-id"))))
        snapshot.activities.size shouldBe 2
      }
    }
  }

  lazy val DUMMY_ACTOR_TYPE_ID: Int = 1

  override val actorTypeToRegions: Map[Int, ActorRef] = {
    Map(
      DUMMY_ACTOR_TYPE_ID -> {
        createPersistentRegion("DummyActor", DummyAgentActor.props)
      }
    )
  }

  def sendMsgToAgentRouteStore(routeDID: String, msg: Any): Unit = {
    platform.routeRegion ! ForIdentifier(routeDID, msg)
  }

  val domainId = generateNewDid().did

  override type StateType = ActivityState
  override def regionActorName: String = ACTIVITY_TRACKER_REGION_ACTOR_NAME
  override def actorEntityId: String = domainId
  override def executionContextProvider: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  override def specificConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"""
      verity.metrics {
        activity-tracking {
          active-user {
            time-windows = ["1 d", "3 d"]
            monthly-window = true
            enabled = true
          }
          active-relationships {
            time-windows = ["7 d"]
            monthly-window = true
            enabled = true
          }
        }
      }
      verity.persistent-actor.base.ActivityTracker {
        snapshot {
          after-n-events = 3
          keep-n-snapshots = 2
          delete-events-on-snapshots = true
        }
      }
      """
    }
  }
}

import com.evernym.verity.actor.agent.user.GetSponsorRel
class DummyAgentActor extends CoreActorExtended {

  override def receiveCmd: Receive = {
    case GetSponsorRel             => sender ! SponsorRel("sponsor-id", "sponsee-id")

  }
}

object DummyAgentActor {
  def props: Props = Props(new DummyAgentActor)
}