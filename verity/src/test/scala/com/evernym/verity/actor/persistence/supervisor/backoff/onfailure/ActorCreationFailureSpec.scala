package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.{IgnoreSupervisorLogErrors, MockActorCreationFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorCreationFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with IgnoreSupervisorLogErrors {

  override def expectDeadLetters = true

  lazy val mockSupervised = system.actorOf(MockActorCreationFailure.backOffOnFailureProps(appConfig))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception" - {
      "should be stopped" in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! Ping(sendBackConfirmation = true)
          expectNoMessage()
        }
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
}