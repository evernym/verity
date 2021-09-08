package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.{GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{GeneratePersistenceFailure, MockActorPersistenceFailure}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.OptionValues
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}

//This test confirms that if any exception occurs during event persisting
// it will be stopped unconditionally (as mentioned in this doc: https://doc.akka.io/docs/akka/current/persistence.html)
// and 'onFailure' won't change anything (as it only reacts to 'Restart' directive)

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
      "should stop and start actor as per BACKOFF strategy" in {
        //will test this Restart strategy: https://github.com/akka/akka/blob/622d8af0ef9f685ee1e91b04177926ca938376ac/akka-actor/src/main/scala/akka/actor/FaultHandling.scala#L211

        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! GeneratePersistenceFailure
          expectNoMessage()
        }
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
            strategy = OnFailure
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
