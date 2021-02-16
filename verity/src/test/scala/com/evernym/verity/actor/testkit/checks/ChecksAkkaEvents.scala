package com.evernym.verity.actor.testkit.checks

import akka.actor.ActorSystem
import akka.event.Logging.{Debug, Error, Info, LogEvent, Warning, simpleName}
import akka.testkit._
import com.evernym.verity.actor.testkit.checks.AkkaEventChecker.isDeadLettersWarning
import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.{Tag, TestSuite}

import scala.collection.Iterable
import scala.language.postfixOps


/** Mix in this trait to get automatic console checking
  */
trait ChecksAkkaEvents extends ChecksForTestSuite {
  this: TestSuite with TestKitBase with BasicSpecBase =>

  def logEventExceptions(e: LogEvent): Boolean = AkkaEventChecker.defaultExceptions(e)
  def expectDeadLetters: Boolean = false

  registerChecker(new AkkaEventChecker(logEventExceptions, expectDeadLetters))
}

class AkkaEventChecker(val isException: LogEvent => Boolean = AkkaEventChecker.defaultExceptions,
                       val expectDeadLetters: Boolean = false)
                      (implicit val system: ActorSystem)
  extends Checker {

  type Issue = String
  type Context = Unit

  // Keeping all the errors in one list(Stream) that happened inside the test case
  var logEvents: Vector[LogEvent] = Vector.empty

  var deadLettersMessages: Vector[LogEvent] = Vector.empty

  // Event filter for error Events using custom block which takes a Partial Function of type PartialFunction[LogEvent,Boolean]
  val errorCaptureFilter: EventFilter = EventFilter.custom {
    case e: Warning if !e.message.isInstanceOf[String] => false //
    case e: Warning if isDeadLettersWarning(e) =>
      deadLettersMessages = deadLettersMessages :+ e
      true
    case e @ (_:Warning | _:Error) =>
      if (isException(e)) {
        true
      } else {
        logEvents = logEvents :+ e
        true // true is used if want to see errors more descriptively, We can also use false if we don't want to clutter our console.
      }
  }

  override def setup(): Unit = {
    logEvents = Vector.empty
  }


  override def wrap[T](ctx: Unit)(test: => T): T = {
    filterEvents(errorCaptureFilter) {
      test
    }
  }

  override def check(setupObj: Unit): Unit = {
    if (logEvents.nonEmpty) throw new FailedCheckException("Akka issues found", logEvents)

    if (!expectDeadLetters && deadLettersMessages.nonEmpty)
      throw new FailedCheckException("Dead Letters found", deadLettersMessages)
  }

  override def teardown(setupObj: Unit): Unit = {
    /*After completion of test, clear log of events*/
    logEvents = Vector.empty // clear all errors
  }

  override def ignoreTagNames: Set[String] = Set(IgnoreAkkaEvents, UNSAFE_IgnoreAkkaEvents).map(_.name)

}

object AkkaEventChecker {
  def defaultExceptions(e: LogEvent): Boolean = false

  def isDeadLettersWarning(e: LogEvent): Boolean = {
    val t = Seq(
      "received dead system",
      "received dead letter"
    )
//      .exists(e.message.toString.startsWith(_))
      .exists { m =>
        e.message.toString.startsWith(m)

      }
    t
  }
}

object IgnoreAkkaEvents extends Tag("IgnoreAkkaEvents")

/**
 * This is a tag (a temporary one) to ignore failures happening due to enabling 'ChecksAkkaEvents' in 'ActorSpecLike'
 * And expectation is that we should fix the error and remove this tag one by one and eventually
 * we should remove this tag itself.
 */
object UNSAFE_IgnoreAkkaEvents extends Tag("UNSAFE_IgnoreAkkaEvents")

class FilteredEventListener(addFilters: Iterable[EventFilter]) extends TestEventListener {
  filters.foreach(addFilter)

  override def print(event: Any): Unit = event match {
    case e: Error   => error(e)
    case _: Warning => //warning(e)
    case _: Info    => //Don't print normal messages
    case _: Debug   => //Don't print normal messages
    case e =>
      warning(Warning(simpleName(this), this.getClass, "received unexpected event of class " + e.getClass + ": " + e))
  }
}
