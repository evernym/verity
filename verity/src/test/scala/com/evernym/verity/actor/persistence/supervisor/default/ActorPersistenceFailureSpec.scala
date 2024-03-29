package com.evernym.verity.actor.persistence.supervisor.default

import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GeneratePersistenceFailure, MockActorPersistenceFailure}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorPersistenceFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockUnsupervised = system.actorOf(
    MockActorPersistenceFailure.props(appConfig, executionContextProvider.futureExecutionContext)
  )

  override def expectDeadLetters: Boolean = true

  "Unsupervised actor" - {

    "when throws an exception during persistence" - {
      "should restart actor once" in {
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

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

