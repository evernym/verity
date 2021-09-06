package com.evernym.verity.actor.persistence.supervisor

import akka.actor.Props
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.wallet.WalletActor
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext


class SupervisorUtilSpec
  extends ActorSpec
    with ProvidesMockPlatform
    with BasicSpec {

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext


  "SupervisorUtil" - {
    "when asked to generate backoff supervisor for AgencyAgent" - {
      "should be able to generate one" in {
        val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          Props(new AgencyAgent(agentActorContext, executionContext))
        )
        onFailureProps.isDefined shouldBe true

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          Props(new AgencyAgent(agentActorContext, executionContext))
        )
        onStopProps.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for AgencyAgentPairwise" - {
      "should be able to generate one" in {
        val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          Props(new AgencyAgentPairwise(agentActorContext, executionContext))
        )
        onFailureProps.isDefined shouldBe true

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          Props(new AgencyAgentPairwise(agentActorContext, executionContext))
        )
        onStopProps.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for UserAgent" - {
      "should be able to generate one" in {

        val actorRegionNames = Set(USER_AGENT_REGION_ACTOR_NAME, "ConsumerAgent", "EnterpriseAgent", "VerityAgent")
        actorRegionNames.foreach { agentRegionName =>
          val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgent(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onFailureProps.isDefined shouldBe true

          val onStopProps = SupervisorUtil.onStopSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgent(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onStopProps.isDefined shouldBe true
        }
      }
    }

    "when asked to generate backoff supervisor for UserAgentPairwise" - {
      "should be able to generate one" in {
        val actorRegionNames = Set(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME, "ConsumerAgentPairwise", "EnterpriseAgentPairwise", "VerityAgentPairwise")
        actorRegionNames.foreach { agentRegionName =>
          val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgentPairwise(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onFailureProps.isDefined shouldBe true

          val onStopProps = SupervisorUtil.onStopSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgentPairwise(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onStopProps.isDefined shouldBe true
        }
      }
    }

    "when asked to generate backoff supervisor for NonPersistent actor" - {
      "should NOT generate backoff supervisor props" in {
        val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
          appConfig,
          NON_PERSISTENT_ACTOR_BASE,
          WALLET_REGION_ACTOR_NAME,
          Props(new WalletActor(agentActorContext.appConfig, agentActorContext.poolConnManager, executionContext))
        )
        onFailureProps.isDefined shouldBe false

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          NON_PERSISTENT_ACTOR_BASE,
          WALLET_REGION_ACTOR_NAME,
          Props(new WalletActor(agentActorContext.appConfig, agentActorContext.poolConnManager, executionContext))
        )
        onStopProps.isDefined shouldBe false
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
    """
  )}

  override def executionContextProvider: ExecutionContextProvider = ecp
}
