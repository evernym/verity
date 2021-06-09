package com.evernym.verity.actor.persistence.recovery.mixed.route_store_migration

import akka.actor.ActorRef
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.msgrouter.legacy.{GetRouteStoreMigrationStatus, RouteStoreMigrationStatus}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.actor.base.EntityIdentifier
import com.evernym.verity.actor.cluster_singleton.ForAgentRoutesMigrator
import com.evernym.verity.actor.cluster_singleton.maintenance.{GetMigrationStatus, MigrationStatusDetail}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, ROUTE_REGION_ACTOR_NAME}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.protocol.engine.DID
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.util.Random

//testing for those systems who has legacy routing actors,
// the new routing actors and legacy route actor migration works fine
// parallely with someone trying to get legacy routes from new actors
class RouteStoreMigrationV1Spec
  extends BaseRecoveryActorSpec
    with Eventually {

  override def beforeAll(): Unit = {
    super.beforeAll()
    routingDataForLegacyActors.foreach { case (did, actorAddressDetail) =>
      storeLegacyAgentRoute(did, actorAddressDetail.actorTypeId, actorAddressDetail.address)
    }
    routingDataForNewActors.foreach { case (did, actorAddressDetail) =>
      storeAgentRoute(did, actorAddressDetail.actorTypeId, actorAddressDetail.address)
    }
    val _ = platform.singletonParentProxy
  }

  "LegacyRoutingAgent actor" - {

    "for newly stored routes" - {
      "when asked for routes which exists in new actor" - {
        "should respond it from RouteStore actor only" in {
          routingDataForNewActors.foreach { case (route, actorAddressDetail) =>
            platform.routeRegion ! ForIdentifier(route, GetStoredRoute)
            val newDetail = expectMsgType[Option[ActorAddressDetail]]
            newDetail.isDefined shouldBe true
            newDetail.get shouldBe actorAddressDetail
            lastSender.path.toString.contains("RouteStore") //responded from new routing actor (confirms route migrated)
          }
        }
      }
    }

    "for routes never stored in any actor" - {
      "when asked to get it" - {
        "should respond with None" in {
          val randomRoute = "randomRoute"
          platform.routeRegion ! ForIdentifier(randomRoute, GetStoredRoute)
          val legacyDetail = expectMsgType[Option[ActorAddressDetail]]
          legacyDetail.isDefined shouldBe false
          lastSender.path.toString.contains("RoutingAgent") //responded from legacy routing actor

          platform.routeRegion ! ForIdentifier(randomRoute, GetStoredRoute)
          val newDetail = expectMsgType[Option[ActorAddressDetail]]
          newDetail.isDefined shouldBe false
          lastSender.path.toString.contains("RoutingAgent") //still responded from legacy routing actor
        }
      }
    }

    "for legacy stored routes" - {
      "when asked for few routes which exists in legacy actor" - {
        "should respond it and also migrate it to new RouteStore actor" in {
          var legacyRoutingActors: Set[ActorRef] = Set.empty
          //sending get route request to few of the route actors which off course won't have it
          // and fallback to legacy route actor which will respond to the request
          // and also migrate that specific route entry to new actor so that next time
          // the same request can be served from the new routing actors only
          routingDataForLegacyActors.take(10).foreach { case (route, actorAddressDetail) =>
            platform.routeRegion ! ForIdentifier(route, GetStoredRoute)
            val legacyDetail = expectMsgType[Option[ActorAddressDetail]]
            legacyDetail.isDefined shouldBe true
            legacyDetail.get shouldBe actorAddressDetail
            legacyRoutingActors += lastSender
            lastSender.path.toString.contains("RoutingAgent") //responded from legacy routing actor
          }

          checkIfMigrationCompleted(legacyRoutingActors)
          checkIfMigrationCompleted(routingDataForLegacyActors)
          checkIfOverallMigrationCompleted()
        }
      }
    }
  }

  //checks migration status completeness only for given legacy agent route actors
  def checkIfMigrationCompleted(legacyActorRefs: Set[ActorRef]): Unit = {
    eventually(timeout(Span(10, Seconds))) {
      legacyActorRefs.foreach { ar =>
        ar ! GetRouteStoreMigrationStatus
        val migrationStatus = expectMsgType[RouteStoreMigrationStatus]
        val currentStatus = migrationStatus.currentStatus
        currentStatus.values.count(_.recorded) shouldBe currentStatus.size
        migrationStatus.isJobScheduled shouldBe false
      }
    }
  }

  //checks migration status completeness only for those legacy routing actors
  // belonging to given routes
  def checkIfMigrationCompleted(routes: Map[DID, ActorAddressDetail]): Unit = {
    eventually(timeout(Span(10, Seconds))) {
      routes.foreach { case (r, aad) =>
        platform.routeRegion ! ForIdentifier(r, GetStoredRoute)
        val newDetail = expectMsgType[Option[ActorAddressDetail]]
        newDetail.isDefined shouldBe true
        newDetail.get shouldBe aad
        val entityIdentifier = EntityIdentifier.parsePath(lastSender.path)
        entityIdentifier.entityType shouldBe ROUTE_REGION_ACTOR_NAME //confirms migrated to new actor
        entityIdentifier.entityId shouldBe r
      }
    }
  }

  //checks if all legacy agent route stores are migrated successfully
  def checkIfOverallMigrationCompleted(): Unit = {
    val msd = eventually(timeout(Span(10, Seconds))) {
      platform.singletonParentProxy ! ForAgentRoutesMigrator(GetMigrationStatus(Option(YES)))
      val msd = expectMsgType[MigrationStatusDetail]
      msd.completed.totalRouteStores shouldBe msd.registered.totalRouteStores
      msd.completed.totalProcessed.contains(msd.registered.totalCandidates) shouldBe true
      msd.status.getOrElse(Map.empty).size shouldBe 100
      msd.isJobScheduled shouldBe false
      msd
    }
    msd.completed.totalProcessed.contains(routingDataForLegacyActors.size) shouldBe true
  }

  val entityTypes = List (
    ACTOR_TYPE_AGENCY_AGENT_ACTOR,
    ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR,
    ACTOR_TYPE_USER_AGENT_ACTOR,
    ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR
  )

  lazy val routingDataForNewActors: Map[DID, ActorAddressDetail] = createRoutingData(Random.nextInt(20))
  lazy val routingDataForLegacyActors: Map[DID, ActorAddressDetail] = createRoutingData(100 + Random.nextInt(200))

  def createRoutingData(totalRoutes: Int): Map[DID, ActorAddressDetail] =
    (1 to totalRoutes).map { _ =>
      val didPair = CommonSpecUtil.generateNewDid()
      val index = Random.nextInt(entityTypes.size-1)
      val actorType = entityTypes(index)
      didPair.DID -> ActorAddressDetail(actorType, didPair.DID)
    }.toMap

  override def overrideSpecificConfig: Option[Config] = Option{
    ConfigFactory.parseString(
      """
         verity.maintenance {
            agent-routes-migrator {
              enabled = true
              scheduled-job {
                interval-in-seconds = 1
              }
              registration {
                batch-size = 50    //how many parallel legacy agent route store actor to ask for registration
              }
              processing {
                batch-size = 50    //how many parallel legacy agent route store actor to be processed for migration
              }
              routes {
                batch-size = 50    //how many parallel routes per legacy agent route actor to be migrated
              }
            }
         }
        """.stripMargin
    )
  }
}
