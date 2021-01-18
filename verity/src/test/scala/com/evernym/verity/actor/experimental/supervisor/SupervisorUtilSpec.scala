package com.evernym.verity.actor.experimental.supervisor

import akka.actor.Props
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.user.UserAgent
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.wallet.WalletActor
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

class SupervisorUtilSpec
  extends ActorSpec
    with ProvidesMockPlatform
    with BasicSpec {

  "SupervisorUtil" - {
    "when asked to generate backoff supervisor for AgencyAgent" - {
      "should be able to generate one" in {
        val props = SupervisorUtil.backoffSupervisorActorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_REGION_ACTOR_NAME,
          Props(new AgencyAgent(agentActorContext))
        )
        props.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for AgencyAgentPairwise" - {
      "should be able to generate one" in {
        val props = SupervisorUtil.backoffSupervisorActorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          Props(new AgencyAgentPairwise(agentActorContext))
        )
        props.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for UserAgent" - {
      "should be able to generate one" in {
        val props = SupervisorUtil.backoffSupervisorActorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          USER_AGENT_REGION_ACTOR_NAME,
          Props(new UserAgent(agentActorContext))
        )
        props.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for UserAgentPairwise" - {
      "should be able to generate one" in {
        val props = SupervisorUtil.backoffSupervisorActorProps(
          appConfig,
          PERSISTENT_ACTOR_BASE,
          USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          Props(new UserAgent(agentActorContext))
        )
        props.isDefined shouldBe true
      }
    }

    "when asked to generate backoff supervisor for NonPersistent actor" - {
      "should NOT generate backoff supervisor props" in {
        val props = SupervisorUtil.backoffSupervisorActorProps(
          appConfig,
          NON_PERSISTENT_ACTOR_BASE,
          WALLET_REGION_ACTOR_NAME,
          Props(new WalletActor(agentActorContext.appConfig, agentActorContext.poolConnManager))
        )
        props.isDefined shouldBe false
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervised-strategy {
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

}
