package com.evernym.verity.actor.testkit.checks.specs

import akka.actor.SupervisorStrategy.Resume
import akka.actor.{Actor, OneForOneStrategy, Props}
import akka.event.Logging.Warning
import ch.qos.logback.classic.Level
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.{AkkaEventChecker, CheckerRegistry, FailedCheckException, UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.testkit._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Second, Span}

import scala.concurrent.duration._
import scala.language.postfixOps

class ChecksAkkaEventsSpec extends ActorSpec with BasicSpec with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(1, Second)), interval = scaled(Span(10, Millis)))

  "An AkkaEventsChecker" - {

    val aec = new AkkaEventChecker
    val cr = new CheckerRegistry(aec)

    val spanishActor = system.actorOf(Props[SpanishActor], "spanish")

    "can detect Warning events" in {

      //val e = intercept[FailedCheckException] {
        cr.run {
          spanishActor ! "hola"
          expectMsg("hola, buenos días")

          spanishActor ! "hello"

          eventually {
            aec.logEvents should not be empty
          }

        }
      //}

      // TODO: Uncomment after fixing AkkaEventChecker.check
      //e.events.head shouldBe a [Warning]
      //e.events.head.asInstanceOf[Warning].message.toString should startWith ("unhandled message from Actor")
      //e.events.head.asInstanceOf[Warning].message.toString should endWith ("hello")
    }

    "can detect Error events" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {

      val madre = system.actorOf(Props[SpanishParent], "madre")
      //val e = intercept[FailedCheckException] {
        cr.run {

          // temporarily silencing logs so intentional error event doesn't show up in the test logs
          runWithLogLevel(Level.OFF) {

            madre ! "morir"

            eventually {
              aec.logEvents should not be empty
            }

          }
        }
      //}

      // TODO: Uncomment after fixing AkkaEventChecker.check
      //e.events.head shouldBe a [Warning]
      //e.events.head.asInstanceOf[Warning].message.toString should equal ("I'm dying [THIS IS A FAKE ERROR FOR TESTING].")

    }

  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class SpanishParent extends Actor {

  override val supervisorStrategy: OneForOneStrategy = {
    OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 10.seconds) {
      case _ => Resume
    }
  }

  private val child = context.actorOf(Props[SpanishActor], "spanishchild")
  def receive: Receive = {
    case msg => child forward msg //forward all messages to the child
  }
}

class SpanishActor() extends Actor {
  def receive: Receive = {
    case "hola" => sender() ! "hola, buenos días"
    case "morir" => throw new RuntimeException("I'm dying [THIS IS A FAKE ERROR FOR TESTING].")
  }
}
