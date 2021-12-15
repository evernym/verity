package com.evernym.verity.actor.cluster_singleton

import java.util.UUID

import akka.actor.{ActorRef, PoisonPill, Props}
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.maintenance.{GetManagerStatus, InitialActorState, ManagerStatus, StartJob, StopJob}
import com.evernym.verity.actor.agent.msghandler.{ActorStateCleanupStatus, FixActorState}
import com.evernym.verity.actor.agent.msgrouter.legacy.{GetRouteBatch, GetRouteBatchResult, LegacySetRoute}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, RoutingAgentUtil}
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.testkit.{CommonSpecUtil, PersistentActorSpec}
import com.evernym.verity.actor.{ForIdentifier, LegacyRouteSet, ShardUtil}
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}


class ActorStateCleanupManagerSpec
  extends PersistentActorSpec
    with BasicSpec
    with ShardUtil
    with Eventually
    with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  val shardSize = 100           //total possible legacy routing actors
  val totalRouteEntries = 250   //total routing entries distributed over 'shardSize'

  "Platform" - {
    "during launch" - {
      "should start required region and other actors" in {
        val _ = platform.routeRegion
        platform.singletonParentProxy
      }
    }
  }

  "AgentRouteStore" - {
    "when sent several SetRoute commands" - {
      "should store the routes successfully" taggedAs UNSAFE_IgnoreLog in {
        //stop the cleanup job until the test creates required data
        platform.singletonParentProxy ! ForActorStateCleanupManager(StopJob)
        expectMsgType[Done.type]

        addRandomRoutes()
        checkRoutes()

        //start the cleanup job so that it can start its processing
        platform.singletonParentProxy ! ForActorStateCleanupManager(StartJob)
        expectMsgType[Done.type]
      }
    }
  }

  "ActorStateCleanupManager" - {

    "when sent GetStatus" - {
      "should respond with correct status" in {
        eventually(timeout(Span(30, Seconds)), interval(Span(100, Millis))) {
          platform.singletonParentProxy ! ForActorStateCleanupManager(GetManagerStatus())
          val status = expectMsgType[ManagerStatus]
          status.registeredRouteStoreActorCount shouldBe shardSize
          status.totalCandidateAgentActors shouldBe totalRouteEntries
        }
      }
    }

    "after some time" - {
      "should have processed all state cleanup" taggedAs (UNSAFE_IgnoreLog, UNSAFE_IgnoreAkkaEvents) in {
        eventually(timeout(Span(50, Seconds)), interval(Span(200, Millis))) {
          platform.singletonParentProxy ! ForActorStateCleanupManager(GetManagerStatus())
          val status = expectMsgType[ManagerStatus]
          status.registeredRouteStoreActorCount shouldBe shardSize
          status.totalCandidateAgentActors shouldBe totalRouteEntries
          status.processedRouteStoreActorCount shouldBe shardSize
          status.totalProcessedAgentActors shouldBe totalRouteEntries
          status.inProgressCleanupStatus.isEmpty shouldBe true
        }
      }
    }
  }

  //route store actor entity id and its stored route mapping
  val entityIdsToRoutes: Map[EntityId, Set[DidStr]] = (1 to totalRouteEntries).map { i =>
    val routeDID = generateDID(i.toString)
    val routeStoreActorEntityId = RoutingAgentUtil.getBucketEntityId(routeDID)
    (routeStoreActorEntityId, routeDID)
  }.groupBy(_._1).mapValues(_.map(_._2).toSet)

  def generateDID(seed: String): DidStr =
    CommonSpecUtil.generateNewDid(Option(UUID.nameUUIDFromBytes(seed.getBytes()).toString)).did

  def addRandomRoutes(): Unit = {
    entityIdsToRoutes.values.map(_.size).sum shouldBe totalRouteEntries
    entityIdsToRoutes.foreach { case (entityId, routeDIDs) =>
      routeDIDs.foreach { routeDID =>
        val setRouteMsg = LegacySetRoute(routeDID, ActorAddressDetail(DUMMY_ACTOR_TYPE_ID, routeDID))
        sendMsgToAgentRouteStore(entityId, setRouteMsg)
        expectMsgType[LegacyRouteSet]
      }
      //just to make sure previous persist events are successfully recorded
      sendMsgToAgentRouteStore(entityId, GetPersistentActorDetail)
      expectMsgType[PersistentActorDetail].totalPersistedEvents shouldBe routeDIDs.size

      //stop actor
      sendMsgToAgentRouteStore(entityId, PoisonPill)
    }
  }

  def checkRoutes(): Unit = {
    entityIdsToRoutes.foreach { case (entityId, routeDIDs) =>
      routeDIDs.zipWithIndex.foreach { case (routeDID, index) =>
        sendMsgToAgentRouteStore(entityId, GetRouteBatch(index, 1))
        val result = expectMsgType[GetRouteBatchResult]
        result.dids shouldBe Set(routeDID)
      }
    }
  }

  def sendMsgToAgentRouteStore(entityId: String, msg: Any): Unit = {
    legacyRouteStoreRegion ! ForIdentifier(entityId, msg)
  }

  lazy val legacyRouteStoreRegion: ActorRef = platform.legacyAgentRouteStoreRegion

  lazy val DUMMY_ACTOR_TYPE_ID: Int = 1

  override val typeToRegions: Map[Int, ActorRef] = {
    Map(
      DUMMY_ACTOR_TYPE_ID -> {
        createPersistentRegion("DummyActor", DummyAgentActor.props)
      }
    )
  }

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
         |          initial-delay-in-seconds = 2
         |          interval-in-seconds = 2
         |        }
         |      }
         |
         |      executor {
         |        batch-size = 1
         |        scheduled-job {
         |          initial-delay-in-seconds = 1
         |          interval-in-seconds = 2
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
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class DummyAgentActor extends CoreActorExtended {

  //this is to create/test scenario wherein some agent actor doesn't recover successfully
  // (or in other words they never responds to the 'FixActorState' command)
  // then also the whole actor state cleanup process should still be working fine.
  val notRespondEntityIds = List("WpGeaHXpyUFLMn3K7rLdD8", "N3gagwN9hYcrBDHQkHYfG4")

  override def receiveCmd: Receive = {
    case fas: FixActorState             =>
      if (! notRespondEntityIds.contains(entityId)) {
        fas.senderActorRef ! InitialActorState(fas.actorDID, isRouteSet = true, 0)
        fas.senderActorRef ! ActorStateCleanupStatus(fas.actorDID, isRouteFixed = true, 0, 0, 0)
      }
  }
}

object DummyAgentActor {
  def props: Props = Props(new DummyAgentActor)
}