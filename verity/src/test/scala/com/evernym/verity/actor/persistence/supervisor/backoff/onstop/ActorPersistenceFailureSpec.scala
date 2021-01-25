package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{MockActorPersistenceFailure, GeneratePersistenceFailure}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorPersistenceFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorPersistenceFailure.props(appConfig))

  "Unsupervised actor" - {

    "when throws an exception during persistence" - {
      "should stop actor" taggedAs UNSAFE_IgnoreAkkaEvents in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockUnsupervised ! GeneratePersistenceFailure
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString (
      s"""
        akka.test.filter-leeway = 15s   # to make the event filter run for little longer time
      """
    ).withFallback(
      AkkaTestBasic.customJournal("com.evernym.verity.actor.persistence.supervisor.GeneratePersistenceFailureJournal")
    )
  }
}

