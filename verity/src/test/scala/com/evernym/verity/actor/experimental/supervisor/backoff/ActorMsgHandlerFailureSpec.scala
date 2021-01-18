package com.evernym.verity.actor.experimental.supervisor.backoff

import akka.actor.Props
import akka.testkit.EventFilter
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.experimental.supervisor.SupervisorUtil
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.config.CommonConfig.PERSISTENT_ACTOR_BASE
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class ActorMsgHandlerFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockSupervised = system.actorOf(
    SupervisorUtil.backoffSupervisorActorProps(
      appConfig,
      PERSISTENT_ACTOR_BASE,
      "MockSupervisor",
      MockActorMsgHandlerFailure.props(appConfig)).get)

  "Supervised actor" - {
    "when throws an unhandled exception during msg handling" - {
      "should be stopped" taggedAs UNSAFE_IgnoreAkkaEvents in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 1) intercept {
          mockSupervised ! ThrowException
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
       verity.persistent-actor.base.supervised-strategy {
          enabled = true
          backoff {
            min-seconds = 3
            max-seconds = 20
            random-factor = 0
          }
      }
      akka.test.filter-leeway = 25s   # to make the event filter run for 25 seconds
      """
  )}
}

class MockActorMsgHandlerFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case ThrowException => throw new ArithmeticException("purposefully throwing exception")
  }

  override def receiveEvent: Receive = ???
}


object MockActorMsgHandlerFailure {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorMsgHandlerFailure(appConfig))
}

case object ThrowException extends ActorMessage