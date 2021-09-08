package com.evernym.verity.actor.persistence.supervisor.backoff.onstop

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild, GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.persistence.supervisor.{MockActorMsgHandlerFailure, ThrowException}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}

//This test confirms that if any `RuntimeException` occurs during message handling
// it will be restarted as per default supervisor strategy
// and 'onStop' backoff doesn't change anything

class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorMsgHandlerFailure.backOffOnStopProps(appConfig, ecp.futureExecutionContext))
  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))


  "OnStop BackoffSupervised actor" - {
    "when throws an unhandled exception during msg handling" - {
      "should restart actor once" in {
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        val expectedLogEntries = 2
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = expectedLogEntries) intercept {
          mockSupervised ! ThrowException
          expectNoMessage()
        }

        eventually (timeoutVal, intervalVal) {
          mockSupervised ! GetCurrentChild
          expectMsgType[CurrentChild].ref.isDefined shouldBe true
        }

        //because it is not restarted by backoff supervisor (rather by default supervisor)
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        mockSupervised ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            strategy = OnStop
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 5s   # to make the event filter run for sufficient time
      """
  )}

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}