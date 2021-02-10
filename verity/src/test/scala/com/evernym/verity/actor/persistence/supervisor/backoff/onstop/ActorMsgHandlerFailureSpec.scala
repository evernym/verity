package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{MockActorMsgHandlerFailure, ThrowException}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorMsgHandlerFailure.backOffOnStopProps(appConfig))

  "OnStop BackoffSupervised actor" - {
    "when throws an unhandled exception during msg handling" - {
      //TODO: find out why we need to use UNSAFE_IgnoreAkkaEvents
      "should restart actor once" taggedAs UNSAFE_IgnoreAkkaEvents in {
        // 1 from 'handleException' in CoreActor and
        // 1 from 'handleFailure' in 'akka.actor.FaultHandling' (the default handler)
        val expectedLogEntries = 2
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockSupervised ! ThrowException
          expectNoMessage()
        }
        //TODO: how to test that the actor is restarted?
        // found some unexplained  behaviour for
        // handling msg failure (the default strategy seems to be Restart)
        // but it doesn't seem to enter into 'preRestart' method in 'CoreActor'
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
      akka.test.filter-leeway = 5s   # to make the event filter run for sufficient time
      """
  )}
}