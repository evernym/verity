package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild, GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GeneratePersistenceFailure, MockActorPersistenceFailure}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.OptionValues
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}


class ActorPersistenceFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually
  with OptionValues {

  lazy val mockSupervised = system.actorOf(
    MockActorPersistenceFailure.backOffOnFailureProps(appConfig, ecp.futureExecutionContext),
    "mockactor"
  )

  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))

  "OnFailure BackoffSupervised actor" - {

    "when throws an exception during persistence" - {
      "should stop and start actor once" in {
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        mockSupervised.path
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! GeneratePersistenceFailure
          expectNoMessage()
        }

        eventually (timeoutVal, intervalVal) {
          mockSupervised ! GetCurrentChild
          expectMsgType[CurrentChild].ref.value
        }

        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 1

        mockSupervised ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]

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
            min-seconds = 1
            max-seconds = 2
            random-factor = 0
          }
        }
      """
    ).withFallback(
      AkkaTestBasic.customJournal("com.evernym.verity.actor.persistence.supervisor.GeneratePersistenceFailureJournal")
    )
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}
