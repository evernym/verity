package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{GenerateRecoveryFailure, MockActorRecoveryFailure}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorRecoveryFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorRecoveryFailure.backOffOnStopProps(appConfig))


  "OnStop BackoffSupervised actor" - {
    "when throws an unhandled exception during recovery" - {
      "should keep restarting as per DEFAULT strategy" in {
        //5 from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler) and
        // 5 from overridden 'preRestart' method in CoreActor
        val expectedLogEntries = 10
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
            strategy = onStop
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 6s   # to make the event filter run for 25 seconds
      akka.mock.actor.exceptionSleepTimeInMillis = 1000
      """
  )}
}
