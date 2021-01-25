package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.MockActorRecoveryFailure
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.config.CommonConfig.PERSISTENT_ACTOR_BASE
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorRecoveryFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockSupervised = system.actorOf(
    SupervisorUtil.onStopBackoffSupervisorActorProps(
      appConfig,
      PERSISTENT_ACTOR_BASE,
      "MockSupervisor",
      MockActorRecoveryFailure.props(appConfig, 1000)).get)


  "OnStop BackoffSupervised actor" - {
    "when throws an unhandled exception during recovery" - {
      "should keep restarting as per DEFAULT strategy" taggedAs UNSAFE_IgnoreAkkaEvents in {    //UNSAFE_IgnoreAkkaEvents is to ignore the unhandled Ping message error message
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 5) intercept {
          mockSupervised ! Ping(sendBackConfirmation = true)
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor-strategy {
          enabled = true
          backoff {
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 5.5s   # to make the event filter run for 25 seconds
      """
  )}
}
