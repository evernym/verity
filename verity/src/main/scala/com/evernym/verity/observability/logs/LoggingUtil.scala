package com.evernym.verity.observability.logs

import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.AgentIdentity
import com.evernym.verity.actor.appStateManager.AppStateConstants.CONTEXT_GENERAL
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI.handleError
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

object LoggingUtil {

  def getAgentIdentityLoggerByClass[T](identityProvider: AgentIdentity, c: Class[T])(implicit as: ActorSystem): Logger = {
    try {
      Logger(
        AgentIdentityLoggerWrapper(
          identityProvider,
          LoggerFactory.getLogger(c)
        )
      )
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger for class '${c.getCanonicalName}': " + Exceptions.getErrorMsg(e)
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg)))
        throw e
    }
  }

  def getAgentIdentityLoggerByName(identityProvider: AgentIdentity, n: String)(implicit as: ActorSystem): Logger = {
    try {
      Logger(
        AgentIdentityLoggerWrapper(
          identityProvider,
          LoggerFactory.getLogger(n)
        )
      )
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger with name '$n': " + Exceptions.getErrorMsg(e)
        AppStateUpdateAPI(as).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg)))
        throw e
    }
  }

  def getLoggerByClass[T](c: Class[T]): Logger = {
    try {
      Logger(c)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger for class '${c.getCanonicalName}': " + Exceptions.getErrorMsg(e)
        handleError(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg)))
        throw e
    }
  }

  def getLoggerByName(n: String): Logger = {
    try {
      Logger(n)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger with name '$n': " + Exceptions.getErrorMsg(e)
        handleError(ErrorEvent(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg)))
        throw e
    }
  }

}
