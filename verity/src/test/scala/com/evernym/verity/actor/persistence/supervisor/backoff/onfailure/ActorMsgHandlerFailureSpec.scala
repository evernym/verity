package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{MockActorMsgHandlerFailure, ThrowException}
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.config.CommonConfig.PERSISTENT_ACTOR_BASE
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockSupervised = system.actorOf(
    SupervisorUtil.onFailureBackoffSupervisorActorProps(
      appConfig,
      PERSISTENT_ACTOR_BASE,
      "MockSupervisor",
      MockActorMsgHandlerFailure.props(appConfig)).get)

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception during msg handling" - {
      //TODO: if we remove 'UNSAFE_IgnoreAkkaEvents', then it fails intermittently
      // need to find out why
      "should stop and start once" taggedAs UNSAFE_IgnoreAkkaEvents in {
        //TODO: test it stops and starts once only
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! ThrowException
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
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
      """
  )}
}
