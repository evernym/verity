package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.GetCurrentChild
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.supervisor.{IgnoreSupervisorLogErrors, MockActorCreationFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}

//This test confirms that if any exception occurs during actor creation itself
// it will be stopped by the default supervisor strategy
// and 'onFailure' supervisor strategy doesn't change that behavior (as it only reacts to 'Restart' directive)

class ActorCreationFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with IgnoreSupervisorLogErrors {

  override def expectDeadLetters = true

  lazy val mockSupervised = system.actorOf(MockActorCreationFailure.backOffOnFailureProps(appConfig, ecp.futureExecutionContext))

  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception during actor creation" - {
      "should be stopped" in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! Ping(sendAck = true)
          expectNoMessage()
        }

        // Supervised actor should have been stopped
        // because exception occurred during actor creation itself
        eventually(timeoutVal, intervalVal) {
          mockSupervised ! GetCurrentChild
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
            strategy = OnFailure
            min-seconds = 1
            max-seconds = 2
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
    """
  )}

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}