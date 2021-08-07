package com.evernym.verity.actor.persistence.recovery.mixed.route_store_migration

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.actor.base.EntityIdentifier
import com.evernym.verity.actor.cluster_singleton.ForAgentRoutesMigrator
import com.evernym.verity.actor.cluster_singleton.maintenance.{GetMigrationStatus, MigrationStatusDetail}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_AGENCY_AGENT_ACTOR, ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR, ACTOR_TYPE_USER_AGENT_ACTOR, ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR, ROUTE_REGION_ACTOR_NAME}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.did.DidStr
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.ExecutionContext
import scala.util.Random

//testing for those systems who has legacy routing actors,
// the new routing actors and legacy route actor migration works fine
class RouteStoreMigrationV3Spec
  extends BaseRecoveryActorSpec
    with Eventually {

  override def beforeAll(): Unit = {
    super.beforeAll()
    routingDataForLegacyActors.foreach { case (did, actorAddressDetail) =>
      storeLegacyAgentRoute(did, actorAddressDetail.actorTypeId, actorAddressDetail.address)
    }
    val _ = platform.singletonParentProxy
  }

  "AgentRoutesMigrator actor" - {
    "after some time" - {
      "should executed migration logic successfully" in {
        checkIfOverallMigrationCompleted()
        checkIfMigrationCompleted(routingDataForLegacyActors)
      }
    }
  }

  //checks migration status completeness only for those legacy routing actors
  // belonging to given routes
  def checkIfMigrationCompleted(routes: Map[DidStr, ActorAddressDetail]): Unit = {
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

  lazy val routingDataForLegacyActors: Map[DidStr, ActorAddressDetail] =
    createRoutingData(100 + Random.nextInt(200))

  def createRoutingData(totalRoutes: Int): Map[DidStr, ActorAddressDetail] =
    (1 to totalRoutes).map { _ =>
      val didPair = CommonSpecUtil.generateNewDid()
      val index = Random.nextInt(entityTypes.size-1)
      val actorType = entityTypes(index)
      didPair.did -> ActorAddressDetail(actorType, didPair.did)
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

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}