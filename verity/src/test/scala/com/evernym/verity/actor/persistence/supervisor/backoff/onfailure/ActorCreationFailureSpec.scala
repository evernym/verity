package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.GetCurrentChild
import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.{IgnoreSupervisorLogErrors, MockActorCreationFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}


class ActorCreationFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with IgnoreSupervisorLogErrors {

  override def expectDeadLetters = true

  lazy val mockSupervised = system.actorOf(MockActorCreationFailure.backOffOnFailureProps(appConfig))

  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception" - {
      "should be stopped" in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! Ping(sendAck = true)
          expectNoMessage()
        }

        // Supervisor should be stopped because the child was stopped
        mockSupervised ! GetCurrentChild
        expectNoMessage()
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            min-seconds = 1
            max-seconds = 2
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
    """
  )}
}