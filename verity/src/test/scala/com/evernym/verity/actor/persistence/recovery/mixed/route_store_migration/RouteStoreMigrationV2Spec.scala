package com.evernym.verity.actor.persistence.recovery.mixed.route_store_migration

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.cluster_singleton.ForAgentRoutesMigrator
import com.evernym.verity.actor.cluster_singleton.maintenance.{GetMigrationStatus, MigrationStatusDetail}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.constants.Constants.YES
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.ExecutionContext

//testing for those systems who doesn't have any legacy routing actors,
// the new routing actors and legacy route actor migration still works fine
class RouteStoreMigrationV2Spec
  extends BaseRecoveryActorSpec
    with Eventually {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = platform.singletonParentProxy
  }

  "AgentRoutesMigrator actor" - {
    "after some time" - {
      "should executed migration logic successfully" in {
        checkIfOverallMigrationCompleted()
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
    msd.completed.totalProcessed.contains(0) shouldBe true
  }

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
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}