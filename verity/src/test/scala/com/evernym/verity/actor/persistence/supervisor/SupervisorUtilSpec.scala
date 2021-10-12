package com.evernym.verity.actor.persistence.supervisor

import akka.actor.Props
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.actor.testkit.{ActorSpec, TestAppConfig}
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.wallet.WalletActor
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsDef
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
        onFailureProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          Props(new AgencyAgent(agentActorContext, executionContext))
        )
        onStopProps.isDefined shouldBe true
        onStopProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnStopSupervisor"
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
        onFailureProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          Props(new AgencyAgentPairwise(agentActorContext, executionContext))
        )
        onStopProps.isDefined shouldBe true
        onStopProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnStopSupervisor"
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
          onFailureProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"

          val onStopProps = SupervisorUtil.onStopSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgent(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onStopProps.isDefined shouldBe true
          onStopProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnStopSupervisor"
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
          onFailureProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"

          val onStopProps = SupervisorUtil.onStopSupervisorProps(
            appConfig,
            PERSISTENT_ACTOR_BASE,
            agentRegionName,
            Props(new UserAgentPairwise(agentActorContext, platform.collectionsMetricsCollector, executionContext))
          )
          onStopProps.isDefined shouldBe true
          onStopProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnStopSupervisor"
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

    "when asked to generate backoff supervisor for protocol actor" - {
      "should generate backoff supervisor props" in {
        val onFailureProps = SupervisorUtil.onFailureSupervisorProps(
          appConfig,
          PERSISTENT_PROTOCOL_CONTAINER,
          "connections-1.0-protocol",
          ActorProtocol(ConnectionsDef).props(agentActorContext, executionContext)
        )
        onFailureProps.isDefined shouldBe true
        onFailureProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"

        val onStopProps = SupervisorUtil.onStopSupervisorProps(
          appConfig,
          PERSISTENT_PROTOCOL_CONTAINER,
          "connections-1.0-protocol",
          ActorProtocol(ConnectionsDef).props(agentActorContext, executionContext)
        )
        onStopProps.isDefined shouldBe true
        onStopProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnStopSupervisor"
      }
    }

    "when asked to generate backoff supervisor for agent actors (when supervision not enabled)" - {
      "should generate backoff supervisor props" in {
        val testAppConfig = new TestAppConfig(
          Option(
            ConfigFactory.parseString(
              """
                |verity.persistent-actor.base.AgencyAgent {
                |  supervisor {
                |    enabled = false
                |  }
                |}
                |""".stripMargin
            )
          ),
          clearValidators = true,
          baseAsFallback = false
        )
        val backOffProps = SupervisorUtil.supervisorProps(
          testAppConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          ActorProtocol(ConnectionsDef).props(agentActorContext, executionContext)
        )
        backOffProps.isDefined shouldBe false
      }
    }

    "when asked to generate backoff supervisor for agent actors (without backoff config)" - {
      "should generate backoff supervisor props" in {
        val testAppConfig = new TestAppConfig(
          Option(
            ConfigFactory.parseString(
              """
                |verity.persistent-actor.base.AgencyAgent {
                |  supervisor {
                |    enabled = true
                |  }
                |}
                |""".stripMargin
            )
          ),
          clearValidators = true,
          baseAsFallback = false
        )
        val backOffProps = SupervisorUtil.supervisorProps(
          testAppConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          ActorProtocol(ConnectionsDef).props(agentActorContext, executionContext)
        )
        backOffProps.isDefined shouldBe true
        backOffProps.get.args.head.toString shouldBe "class akka.pattern.internal.BackoffOnRestartSupervisor"
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
