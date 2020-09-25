package com.evernym.verity.actor.testkit.checks

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging.{Debug, Error, Info, InitializeLogger, LogEvent, LoggerInitialized, Warning, simpleName}
import akka.pattern.ask
import akka.testkit._
import akka.util.Timeout
import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.{Tag, TestSuite}

import scala.collection.Iterable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


/** Mix in this trait to get automatic console checking
  */
trait ChecksAkkaEvents extends ChecksForTestSuite {
  this: TestSuite with TestKitBase with BasicSpecBase =>

  registerChecker(new AkkaEventChecker)
}

class AkkaEventChecker(implicit val system: ActorSystem) extends Checker {

  type Issue = String
  type Context = Unit

  // Keeping all the errors in one list(Stream) that happened inside the test case
  var logEvents: Vector[LogEvent] = Vector.empty

  // Event filter for error Events using custom block which takes a Partial Function of type PartialFunction[LogEvent,Boolean]
  val errorCaptureFilter: EventFilter = EventFilter.custom {
    case e @ (_:Warning | _:Error) =>
      logEvents = logEvents :+ e
      true // true is used if want to see errors more descriptively, We can also use false if we don't want to clutter our console.
  }

  // Registering a Test actor which will be subscribed to a EventStream (ErrorEventStream)
  lazy val testListener: ActorRef = {
    val lsnr = system.actorOf(Props(new FilteredEventListener(Seq(errorCaptureFilter))))

    val fut = ask(lsnr, InitializeLogger(system.eventStream))(Timeout(1 second))

    val response = Await.result(fut, 1 second)
    response match {
      case LoggerInitialized => //do nothing
      case r => throw new RuntimeException(s"unexpected response from Logger actor: $r")
    }

    lsnr

  }

  override def setup(): Unit = {
    logEvents = Vector.empty

    /*Subscribing an EventStream of type Error*/
    system.eventStream.subscribe(testListener, classOf[Error])
  }


  override def wrap[T](ctx: Unit)(test: => T): T = {
    filterEvents(errorCaptureFilter) {
      test
    }
  }

  override def check(setupObj: Unit): Unit = {
    if (logEvents.nonEmpty) throw new FailedCheckException("Akka issues found", logEvents)
  }

  override def teardown(setupObj: Unit): Unit = {
    /*After completion of test un-subscribe from EventStream and clear the Stream*/
    system.eventStream.unsubscribe(testListener, classOf[Error])
    logEvents = Vector.empty // clear all errors
  }

  override def ignoreTagNames: Set[String] = Set(IgnoreAkkaEvents, UNSAFE_IgnoreAkkaEvents).map(_.name)

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
