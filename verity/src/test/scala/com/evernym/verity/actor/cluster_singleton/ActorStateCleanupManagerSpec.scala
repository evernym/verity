package com.evernym.verity.actor.cluster_singleton

import java.util.UUID

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.agent.maintenance.{GetManagerStatus, InitialActorState, ManagerStatus}
import com.evernym.verity.actor.agent.msghandler.{ActorStateCleanupStatus, FixActorState}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, RoutingAgentUtil, SetRoute}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.testkit.{CommonSpecUtil, PersistentActorSpec}
import com.evernym.verity.actor.{ForIdentifier, RouteSet, ShardUtil}
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}


class ActorStateCleanupManagerSpec
  extends PersistentActorSpec
    with BasicSpec
    with ShardUtil
    with Eventually {

  //number of total route store actors
  val shardSize = 100         //total possible actors
  val totalRouteEntries = 30

  "Platform" - {
    "during launch" - {
      "should start required region and other actors" in {
        val _ = platform.agentRouteStoreRegion
        platform.singletonParentProxy
      }
    }
  }

  "AgentRouteStore" - {
    "when sent several SetRoute commands" - {
      "should store the routes successfully" taggedAs UNSAFE_IgnoreLog in {
        addRandomRoutes()
      }
    }
  }

  "ActorStateCleanupManager" - {

    "when sent GetStatus" - {
      "should respond with correct status" in {
        eventually(timeout(Span(20, Seconds)), interval(Span(4, Seconds))) {
          platform.singletonParentProxy ! ForActorStateCleanupManager(GetManagerStatus())
          val status = expectMsgType[ManagerStatus]
          status.registeredRouteStoreActorCount shouldBe shardSize
          status.totalCandidateAgentActors shouldBe totalRouteEntries
        }
      }
    }

    "after some time" - {
      "should have processed all state cleanup" taggedAs (UNSAFE_IgnoreLog, UNSAFE_IgnoreAkkaEvents) in {
        eventually(timeout(Span(30, Seconds)), interval(Span(4, Seconds))) {
          platform.singletonParentProxy ! ForActorStateCleanupManager(GetManagerStatus())
          val status = expectMsgType[ManagerStatus]
          status.registeredRouteStoreActorCount shouldBe shardSize
          status.totalCandidateAgentActors shouldBe totalRouteEntries
          status.processedRouteStoreActorCount shouldBe shardSize
          status.totalProcessedAgentActors shouldBe totalRouteEntries
        }
      }
    }
  }


  val entityIdsToRoutes: Map[String, Set[DID]] = (1 to totalRouteEntries).map { i =>
    val routeDID = generateDID(i.toString)
    val entityId = RoutingAgentUtil.getBucketEntityId(routeDID)
    (entityId, routeDID)
  }.groupBy(_._1).mapValues(_.map(_._2).toSet)

  def generateDID(seed: String): DID =
    CommonSpecUtil.generateNewDid(Option(UUID.nameUUIDFromBytes(seed.getBytes()).toString)).DID

  def addRandomRoutes(): Unit = {
    entityIdsToRoutes.values.map(_.size).sum shouldBe totalRouteEntries
    entityIdsToRoutes.foreach { case (entityId, routeDIDs) =>
      routeDIDs.foreach { routeDID =>
        val setRouteMsg = SetRoute(routeDID, ActorAddressDetail(DUMMY_ACTOR_TYPE_ID, routeDID))
        sendMsgToAgentRouteStore(entityId, setRouteMsg)
        expectMsgType[RouteSet]
      }
      //just to make sure previous persist events are successfully recorded
      sendMsgToAgentRouteStore(entityId, GetPersistentActorDetail)
      expectMsgType[PersistentActorDetail].totalPersistedEvents shouldBe routeDIDs.size

      //stop the actor
      sendMsgToAgentRouteStore(entityId, PoisonPill)
    }
  }

  def sendMsgToAgentRouteStore(entityId: String, msg: Any): Unit = {
    routeStoreRegion ! ForIdentifier(entityId, msg)
  }

  lazy val routeStoreRegion: ActorRef = platform.agentRouteStoreRegion

  lazy val DUMMY_ACTOR_TYPE_ID: Int = ACTOR_TYPE_USER_AGENT_ACTOR

  override lazy val mockRouteStoreActorTypeToRegions = Map(
    ACTOR_TYPE_USER_AGENT_ACTOR -> {
      createPersistentRegion("DummyActor", DummyAgentActor.props)
      ClusterSharding(system).shardRegion("DummyActor")
    }
  )

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"""
         |verity {
         |  agent {
         |    actor-state-cleanup {
         |      enabled = true
         |
         |      manager {
         |        registration {
         |          batch-size = 100
         |          batch-item-sleep-interval-in-millis = 0
         |        }
         |
         |        processor {
         |          batch-size = 100
         |          batch-item-sleep-interval-in-millis = 0
         |        }
         |
         |        scheduled-job {
         |          initial-delay-in-seconds = 10
         |          interval-in-seconds = 2
         |        }
         |      }
         |
         |      executor {
         |        batch-size = 1
         |        scheduled-job {
         |          initial-delay-in-seconds = 1
         |          interval-in-seconds = 3
         |        }
         |      }
         |    }
         |
         |    migrate-thread-contexts {
         |      scheduled-job {
         |        initial-delay-in-seconds = -1
         |      }
         |    }
         |  }
         |}
         """.stripMargin
    }
  }
}

class DummyAgentActor extends CoreActorExtended {
  override def receiveCmd: Receive = {
    case fas: FixActorState             =>
      fas.senderActorRef ! InitialActorState(fas.actorDID, isRouteSet = true, 1)
      fas.senderActorRef ! ActorStateCleanupStatus(fas.actorDID, isRouteFixed = true, 0, 1, 0)
  }
}

object DummyAgentActor {
  def props: Props = Props(new DummyAgentActor)
}