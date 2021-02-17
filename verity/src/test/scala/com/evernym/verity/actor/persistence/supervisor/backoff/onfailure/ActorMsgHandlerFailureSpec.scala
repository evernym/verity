package com.evernym.verity.actor.persistence.supervisor.backoff.onfailure

import akka.pattern.BackoffSupervisor.{CurrentChild, GetCurrentChild, GetRestartCount, RestartCount}
import akka.testkit.EventFilter
import com.evernym.verity.actor.persistence.supervisor.{MockActorMsgHandlerFailure, ThrowException}
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Milliseconds, Seconds, Span}


class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockSupervised = system.actorOf(MockActorMsgHandlerFailure.backOffOnFailureProps(appConfig))

  val timeoutVal: PatienceConfiguration.Timeout = timeout(Span(10, Seconds))
  val intervalVal: PatienceConfiguration.Interval = interval(Span(100, Milliseconds))

  "OnFailure BackoffSupervised actor" - {
    "when throws an unhandled exception during msg handling" - {
      "should stop and start (not exactly a restart) once" in {
        mockSupervised ! GetRestartCount
        expectMsgType[RestartCount].count shouldBe 0

        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! ThrowException
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

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervisor {
          enabled = true
          backoff {
            min-seconds = 1
            max-seconds = 2
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
      """
  )}
}
