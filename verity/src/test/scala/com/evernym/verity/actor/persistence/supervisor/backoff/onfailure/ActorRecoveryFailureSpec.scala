package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{GenerateRecoveryFailure, IgnoreSupervisorLogErrors, MockActorRecoveryFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorRecoveryFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with IgnoreSupervisorLogErrors {


  override def expectDeadLetters: Boolean = true

  lazy val mockSupervised = system.actorOf(MockActorRecoveryFailure.backOffOnFailureProps(appConfig))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception during recovery" - {
      "should stop and start (not exactly a restart) as per BACKOFF strategy" in {
        //4 from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler)
        val expectedLogEntries = 4
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockSupervised ! GenerateRecoveryFailure
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


