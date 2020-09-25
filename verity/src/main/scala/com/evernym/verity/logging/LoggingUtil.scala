package com.evernym.verity.logging

import com.evernym.verity.actor.agent.AgentIdentity
import com.evernym.verity.apphealth.AppStateConstants.CONTEXT_GENERAL
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, SeriousSystemError}
import com.evernym.verity.Exceptions
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

object LoggingUtil {

  def getAgentIdentityLoggerByClass[T](identityProvider: AgentIdentity, c: Class[T]): Logger = {
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
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg))
        throw e
    }
  }

  def getAgentIdentityLoggerByName(identityProvider: AgentIdentity, n: String): Logger = {
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
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg))
        throw e
    }
  }

  def getLoggerByClass[T](c: Class[T]): Logger = {
    try {
      Logger(c)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger for class '${c.getCanonicalName}': " + Exceptions.getErrorMsg(e)
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg))
        throw e
    }
  }

  def getLoggerByName(n: String): Logger = {
    try {
      Logger(n)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger with name '$n': " + Exceptions.getErrorMsg(e)
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_GENERAL, e, Option(errorMsg))
        throw e
    }
  }

}
