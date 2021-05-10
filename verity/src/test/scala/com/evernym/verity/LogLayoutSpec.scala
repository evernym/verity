package com.evernym.verity

import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent}
import ch.qos.logback.classic.{Level, Logger}
import com.evernym.verity.Status._
import com.evernym.verity.testkit.BasicSpec
import org.mockito.scalatest.MockitoSugar
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.JavaConverters._


class LogLayoutSpec extends BasicSpec with MockitoSugar {
  val timestampString = "2019-06-21T19:55:32.12Z"
  val event: ILoggingEvent = mock[ILoggingEvent]

  val callerData = new Array[StackTraceElement](1)
  callerData(0) = new StackTraceElement("fake-class", "fake-name", "fake-file", 1)
  val arguments = new Array[Object](1)
  arguments(0) = ("argName", "argValue")


  when(event.getCallerData).thenReturn(callerData)
  when(event.getThreadName).thenReturn("test")
  when(event.getLevel).thenReturn(Level.INFO)
  when(event.getLoggerName).thenReturn("layoutLogger")
  when(event.getArgumentArray).thenReturn(arguments)

  val logLayout = new LogLayout()

  "when a log message is written" - {

    "when the timestamp has a 3 digit millisecond value" - {
      "should generate a log message with a 3 digit millisecond value" in {
        when(event.getTimeStamp).thenReturn(Instant.parse(timestampString).toEpochMilli)
        val logLine = logLayout.doLayout(event)
        logLine shouldBe "lvl=\"INFO\" ts=\"2019-06-21T19:55:32.120Z[UTC]\" thread=\"test\" logger=\"layoutLogger\" src=\"fake-file:1\" msg=\"\" argName=\"argValue\"\n"
      }
    }
    "when the timestamp has no millisecond value" - {
      "should generate a log message with a 3 digit millisecond value padded with 3 zeros" in {
        when(event.getTimeStamp).thenReturn(Instant.parse(timestampString).truncatedTo(ChronoUnit.SECONDS).toEpochMilli)
        val logLine = logLayout.doLayout(event)
        logLine shouldBe "lvl=\"INFO\" ts=\"2019-06-21T19:55:32.000Z[UTC]\" thread=\"test\" logger=\"layoutLogger\" src=\"fake-file:1\" msg=\"\" argName=\"argValue\"\n"
      }
    }
  }
}


class LogLayout2Spec extends BasicSpec {

  private val logger = LoggerFactory.getLogger("test")
  "LogLayout should" - {
    "handle empty event" in {
      val e = new LoggingEvent()
      val l = new LogLayout()

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("lvl=")
      out.trim should include("logger=")
      out.trim should include("msg=")
    }

    "handle normal event" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object]()
      )

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("""lvl="INFO"""")
      out.trim should include("""logger="test"""")
      out.trim should include("""msg="message"""")
    }

    "handle single element Arg Array" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object]("Test")
      )

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("""lvl="INFO"""")
      out.trim should include("""logger="test"""")
      out.trim should include("""msg="message"""")
    }

    "handle tuple arg array" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object](("arg", "test"))
      )

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("""lvl="INFO"""")
      out.trim should include("""logger="test"""")
      out.trim should include("""msg="message" arg="test"""")
    }

    "handle tuple arg array with value being Any" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object](("arg1", "value1"), ("arg2", UNHANDLED), ("arg3", FORBIDDEN))
      )

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("""lvl="INFO"""")
      out.trim should include("""logger="test"""")
      out.trim should include(s"""msg="message" arg1="value1" arg2="${UNHANDLED.toString()}" arg3="${FORBIDDEN.toString()}"""")
    }

    "handle mdc map" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object](("arg1", "value1"), ("arg2", UNHANDLED), ("arg3", FORBIDDEN))
      )

      e.setMDCPropertyMap(Map("domain_did" -> "TSTQQCnj4ZuKtoPdYX1HYB").asJava)

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("ts=")
      out.trim should include("thread=")
      out.trim should include("""lvl="INFO"""")
      out.trim should include("""logger="test"""")
      out.trim should include("""domain_did="TSTQQCnj4ZuKtoPdYX1HYB"""")
      out.trim should include(s"""msg="message" arg1="value1" arg2="${UNHANDLED.toString()}" arg3="${FORBIDDEN.toString()}"""")
    }

    val multiLineMessage = """agency-service: Response for
      |  Request : HttpRequest(HttpMethod(GET),http://localhost:9003/agency/heartbeat,List(Timeout-Access: <function1>, Remote-Address, Host, User-Agent: Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:87.0) Gecko/20100101 Firefox/87.0, Accept: text/html, application/xhtml+xml, application/xml;q=0.9, image/webp, */*;q=0.8, Accept-Language: en-US, en;q=0.5, Accept-Encoding: gzip, deflate, Connection: keep-alive, Cookie, Upgrade-Insecure-Requests: 1, DNT: 1, Sec-GPC: 1, Cache-Control: max-age=0),HttpEntity.Strict(none/none,0 bytes total),HttpProtocol(HTTP/1.1))
      |  Response: Rejected(List())""".stripMargin

    "handle multi-line msg" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        multiLineMessage,
        null,
        Array[Object](("arg1", "value1"), ("arg2", UNHANDLED), ("arg3", FORBIDDEN))
      )

      val out = l.doLayout(e)

      out.trim should not include "\n"
    }


    "convert kamon traceid and spanid" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object](("arg1", "value1"), ("arg2", UNHANDLED), ("arg3", FORBIDDEN))
      )

      e.setMDCPropertyMap(
        Map(
          "kamonSpanId" -> "4519eea8b9532d0f",
          "kamonTraceId" -> "c5006a01d4cd6223"
        ).asJava)

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("""kamonSpanId="4979273271465946383"""")
      out.trim should include("""kamonTraceId="14195462581569544739"""")
    }

    "handle non-hex or numeric kamon ids" in {
      val l = new LogLayout()

      val log = logger.asInstanceOf[Logger]

      val e = new LoggingEvent(
        "ch.qos.logback.core.ConsoleAppender",
        log,
        Level.INFO,
        "message",
        null,
        Array[Object](("arg1", "value1"), ("arg2", UNHANDLED), ("arg3", FORBIDDEN))
      )

      e.setMDCPropertyMap(
        Map(
          "kamonSpanId" -> "4979273271465946383",
          "kamonTraceId" -> "a_non-$-numeric_id"
        ).asJava)

      val out = l.doLayout(e)
      out should not be null
      out.trim should include("""kamonSpanId="4979273271465946383"""")
      out.trim should include("""kamonTraceId="a_non-$-numeric_id"""")
    }
  }
}
