package com.evernym.verity.actor.experimental.supervisor.default

import akka.actor.Props
import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Ping
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.language.postfixOps


class ActorRecoveryFailureSpec
  extends ActorSpec
  with BasicSpec
  with Eventually {

  lazy val mockUnsupervised = system.actorOf(MockActorRecoveryFailure.props(appConfig))

  "Unsupervised actor" - {
    "when throws an unhandled exception during actor recovery" - {
      "should be keep restarting (in our case it would be every 1 second)" taggedAs UNSAFE_IgnoreAkkaEvents in {
        EventFilter.error(pattern = "purposefully throwing exception", occurrences = 5) intercept {
          mockUnsupervised ! Ping(sendBackConfirmation = true)
          expectNoMessage()
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option { ConfigFactory.parseString (
    """
      akka.test.filter-leeway = 6s   # to make the event filter run for little longer time
      """
  )}
}

class MockActorRecoveryFailure(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case "unhandled" => //nothing to do
  }

  override def receiveEvent: Receive = ???

  override def postActorRecoveryCompleted(): List[Future[Any]] = {
    Thread.sleep(1000) //to control the exception throw flow to be able to better test occurrences of failures
    throw new ArithmeticException("purposefully throwing exception")
  }
}

object MockActorRecoveryFailure {
  def props(appConfig: AppConfig): Props =
    Props(new MockActorRecoveryFailure(appConfig))
}

