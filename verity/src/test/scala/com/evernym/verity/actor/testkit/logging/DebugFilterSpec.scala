package com.evernym.verity.actor.testkit.logging

import akka.actor.ActorRef
import akka.event.Logging._
import akka.testkit.{EventFilter, ImplicitSender}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually


class DebugFilterSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {


  lazy val actor: ActorRef = system.actorOf(MockLoggerActor.props)

  "With akka log level set as DEBUG" - {

    "when tried to log message at DEBUG level" - {
      "should be able to caught by EventFilter" in {
        val logMsgCmd = LogMsg("debug level test", DebugLevel)
        EventFilter.debug(pattern = s"$SCALA_LOGGER_MSG_PREFIX${logMsgCmd.msg}", occurrences = 0) intercept {
          EventFilter.debug(pattern = s"${logMsgCmd.msg}", occurrences = 1) intercept {
            actor ! logMsgCmd
            expectMsg(Done)
          }
        }
      }
    }

    "when tried to log message at INFO level" - {
      "should be able to caught by EventFilter" in {
        val logMsgCmd = LogMsg("info level test", InfoLevel)
        EventFilter.info(pattern = s"$SCALA_LOGGER_MSG_PREFIX${logMsgCmd.msg}", occurrences = 0) intercept {
          EventFilter.info(pattern = s"${logMsgCmd.msg}", occurrences = 1) intercept {
            actor ! logMsgCmd
            expectMsg(Done)
          }
        }
      }
    }

    "when tried to log message at WARNING level" - {
      "should be able to caught by EventFilter" in {
        val logMsgCmd = LogMsg("warning level test", WarningLevel)
        EventFilter.warning(pattern = s"$SCALA_LOGGER_MSG_PREFIX${logMsgCmd.msg}", occurrences = 0) intercept {
          EventFilter.warning(pattern = s"${logMsgCmd.msg}", occurrences = 1) intercept {
            actor ! logMsgCmd
            expectMsg(Done)
          }
        }
      }
    }

    "when tried to log message at ERROR level" - {
      "should be able to caught by EventFilter" in {
        val logMsgCmd = LogMsg("error level test", ErrorLevel)
        EventFilter.error(pattern = s"$SCALA_LOGGER_MSG_PREFIX${logMsgCmd.msg}", occurrences = 0) intercept {
          EventFilter.error(pattern = s"${logMsgCmd.msg}", occurrences = 1) intercept {
            actor ! logMsgCmd
            expectMsg(Done)
          }
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """
          |akka.test.filter-leeway = 10s   # to make the event filter run for little longer time
          |akka.loglevel = DEBUG
          |akka.logging-filter = "com.evernym.verity.actor.testkit.logging.TestFilter"
        |""".stripMargin
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

