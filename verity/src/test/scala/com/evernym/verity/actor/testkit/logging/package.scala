package com.evernym.verity.actor.testkit

import akka.actor.Props
import akka.event.Logging.{DebugLevel, ErrorLevel, InfoLevel, LogLevel, WarningLevel}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

package object logging {

  val SCALA_LOGGER_MSG_PREFIX = "FROM SCALA LOGGER: "

  object MockLoggerActor {
    def props: Props = Props(new MockLoggerActor())
  }


  /**
   * a mock actor which logs messages with "akka LoggingAdapter provided logger" and
   * "scala logging provided logger"
   */
  class MockLoggerActor
    extends CoreActorExtended {

    val logger: Logger = getLoggerByClass(getClass)

    override def receiveCmd: Receive = {
      case LogMsg(msg, level) => level match {
        case DebugLevel       => log.debug(msg); logger.debug(s"$SCALA_LOGGER_MSG_PREFIX$msg"); sender() ! Done
        case InfoLevel        => log.info(msg); logger.info(s"$SCALA_LOGGER_MSG_PREFIX$msg"); sender() ! Done
        case WarningLevel     => log.warning(msg); logger.warn(s"$SCALA_LOGGER_MSG_PREFIX$msg"); sender() ! Done
        case ErrorLevel       => log.error(msg); logger.error(s"$SCALA_LOGGER_MSG_PREFIX$msg"); sender() ! Done
        case other            => throw new RuntimeException("unsupported log level: " + other)
      }
    }
  }

  case class LogMsg(msg: String, level: LogLevel) extends ActorMessage
}
