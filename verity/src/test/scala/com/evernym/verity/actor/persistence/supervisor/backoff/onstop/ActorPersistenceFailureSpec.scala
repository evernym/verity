package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild, GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GeneratePersistenceFailure, MockActorPersistenceFailure}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}

//This test confirms that if any exception occurs during event persisting
// it will be stopped unconditionally (as mentioned in this doc: https://doc.akka.io/docs/akka/current/persistence.html)
// and 'onStop' will try to restart it based on the backoff strategy

class ActorPersistenceFailureSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorPersistenceFailure.backOffOnStopProps(appConfig, ecp.futureExecutionContext))
  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))

  override def expectDeadLetters: Boolean = true

  "OnStop BackoffSupervised actor" - {

    "when throws an exception during persistence" - {
      "should restart actor once" in {
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! GeneratePersistenceFailure
          expectNoMessage()
        }

        eventually (timeoutVal, intervalVal) {
          mockSupervised ! GetCurrentChild
          expectMsgType[CurrentChild].ref.isDefined shouldBe true
        }

        //because it is not restarted by backoff supervisor (rather by default supervisor)
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
            strategy = OnStop
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

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

