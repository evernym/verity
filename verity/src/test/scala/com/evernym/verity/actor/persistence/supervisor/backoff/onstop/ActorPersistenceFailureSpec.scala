package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{GeneratePersistenceFailure, MockActorPersistenceFailure}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorPersistenceFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorPersistenceFailure.backOffOnStopProps(appConfig))

  override def expectDeadLetters: Boolean = true


  "OnStop BackoffSupervised actor" - {

    "when throws an exception during persistence" - {
      "should restart actor once" in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockUnsupervised ! GeneratePersistenceFailure
          expectNoMessage()
        }
        //TODO: how to test that the actor is restarted?
        // found some unexplained  behaviour for
        // handling persistence failure (the default strategy seems to be Restart)
        // but it doesn't seem to enter into 'preRestart' method in 'CoreActor'
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString (
      s"""
        akka.test.filter-leeway = 15s   # to make the event filter run for little longer time
        verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            strategy = onStop
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
        }
      """
    ).withFallback(
      AkkaTestBasic.customJournal("com.evernym.verity.actor.persistence.supervisor.GeneratePersistenceFailureJournal")
    )
  }
}

