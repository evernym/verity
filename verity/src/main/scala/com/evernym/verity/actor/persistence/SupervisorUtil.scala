package com.evernym.verity.actor.persistence

import akka.actor.Props
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import com.evernym.verity.actor.persistence.SupervisorUtil.BackoffStrategy
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object SupervisorUtil {

  val logger: Logger = getLoggerByName("SupervisorUtil")

  val SUPERVISED_ACTOR_NAME: String = "supervised"

  class InvalidStrategy(msg: String) extends Exception(msg)

  sealed trait BackoffStrategy
  object BackoffStrategy {
    def fromString(s: String): Try[BackoffStrategy] = {
      s.trim.toLowerCase match {
        case "onfailure" => Success(OnFailure)
        case "onstop" => Success(OnStop)
        case s => Failure(new InvalidStrategy("Unknown backoff strategy - 's'"))
      }
    }
  }

  case object OnFailure extends BackoffStrategy
  case object OnStop extends BackoffStrategy

  def supervisorProps(appConfig: AppConfig,
                      entityCategory: String,
                      typeName: String,
                      childProps: Props): Option[Props] = {
    getBackoffConfig(appConfig, entityCategory, typeName)
    .map { config =>
      config.strategy match {
        case OnFailure => onFailureSupervisorProps(config, childProps)
        case OnStop => onStopSupervisorProps(config, childProps)
      }
    }
  }

  def onFailureSupervisorProps(appConfig: AppConfig,
                               entityCategory: String,
                               typeName: String,
                               childProps: Props): Option[Props] = {
    getBackoffConfig(appConfig, entityCategory, typeName)
    .map (onFailureSupervisorProps(_,childProps))
  }

  private def onFailureSupervisorProps(conf: BackoffConfig,
                                       childProps: Props): Props = {
    BackoffSupervisor.props(
      BackoffOpts
        .onFailure(
          childProps,
          childName = SUPERVISED_ACTOR_NAME,
          minBackoff = conf.minBackoffSeconds.seconds,
          maxBackoff = conf.maxBackOffSeconds.seconds,
          randomFactor = conf.randomFactor)
        .withMaxNrOfRetries(conf.maxNrOfRetries)
    )
  }

  def onStopSupervisorProps(appConfig: AppConfig,
                            entityCategory: String,
                            typeName: String,
                            childProps: Props): Option[Props] = {
    getBackoffConfig(appConfig, entityCategory, typeName)
    .map (onStopSupervisorProps(_, childProps))
  }

  private def onStopSupervisorProps(conf: BackoffConfig,
                                    childProps: Props): Props = {
    BackoffSupervisor.props(
      BackoffOpts
        .onStop(
          childProps,
          childName = SUPERVISED_ACTOR_NAME,
          minBackoff = conf.minBackoffSeconds.seconds,
          maxBackoff = conf.maxBackOffSeconds.seconds,
          randomFactor = conf.randomFactor)
        .withMaxNrOfRetries(conf.maxNrOfRetries)
    )
  }

  private def getBackoffConfig(appConfig: AppConfig, entityCategory: String, typeName: String): Option[BackoffConfig] = {
    val supervisedEnabled =
      PersistentActorConfigUtil
        .getSupervisedEnabled(appConfig, defaultValue = false, entityCategory, typeName)
    if (supervisedEnabled) {
      val strategy = {
        val confVal = PersistentActorConfigUtil.getBackoffStrategy(
          appConfig,
          OnFailure.toString,
          entityCategory,
          typeName
        )
        BackoffStrategy
          .fromString(confVal)
          .recover {
            case e: InvalidStrategy =>
              logger.error("Using OnFailure because unknown strategy defined:", e)
              OnFailure
          }
          .getOrElse {
            OnFailure
          }
      }

      val minBackoffSeconds = PersistentActorConfigUtil.getBackoffMinSeconds(
        appConfig,
        3,
        entityCategory,
        typeName
      ) //TODO: finalize number

      val maxBackoffSeconds = PersistentActorConfigUtil.getBackoffMaxSeconds(
        appConfig,
        100,
        entityCategory,
        typeName
      ) //TODO: finalize number

      val randomFactor = PersistentActorConfigUtil.getBackoffRandomFactor(
        appConfig,
        0.2,
        entityCategory,
        typeName
      ) //TODO: finalize number

      val maxNrOfRetries = PersistentActorConfigUtil.getBackoffMaxNrOfRetries(
        appConfig,
        10,
        entityCategory,
        typeName
      ) //TODO: finalize number

      Some(
        BackoffConfig(
          strategy,
          minBackoffSeconds,
          maxBackoffSeconds,
          randomFactor,
          maxNrOfRetries
        )
      )

    } else None
  }
}

case class BackoffConfig(strategy: BackoffStrategy,
                         minBackoffSeconds: Int,
                         maxBackOffSeconds: Int,
                         randomFactor: Double,
                         maxNrOfRetries: Int)