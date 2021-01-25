package com.evernym.verity.actor.persistence

import akka.actor.Props
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import com.evernym.verity.config.AppConfig

import scala.concurrent.duration._

object SupervisorUtil {

  val SUPERVISED_ACTOR_NAME = "supervised"

  def onFailureBackoffSupervisorActorProps(appConfig: AppConfig,
                                           entityCategory: String,
                                           typeName: String,
                                           childProps: Props): Option[Props] = {
    getBackoffConfig(appConfig, entityCategory, typeName).map { bc =>
      //this will create instance of 'BackoffOnFailureSupervisor' actor
      BackoffSupervisor.props(
        BackoffOpts
          .onFailure(
            childProps,
            childName = SUPERVISED_ACTOR_NAME,
            minBackoff = bc.minBackoffSeconds.seconds,
            maxBackoff = bc.maxBackOffSeconds.seconds,
            randomFactor = bc.randomFactor)
          .withMaxNrOfRetries(bc.maxNrOfRetries)
      )
    }
  }

  def onStopBackoffSupervisorActorProps(appConfig: AppConfig,
                                        entityCategory: String,
                                        typeName: String,
                                        childProps: Props): Option[Props] = {
    getBackoffConfig(appConfig, entityCategory, typeName).map { bc =>
      //this will create instance of 'BackoffOnStopSupervisor' actor
      BackoffSupervisor.props(
        BackoffOpts
          .onStop(
            childProps,
            childName = SUPERVISED_ACTOR_NAME,
            minBackoff = bc.minBackoffSeconds.seconds,
            maxBackoff = bc.maxBackOffSeconds.seconds,
            randomFactor = bc.randomFactor)
          .withMaxNrOfRetries(bc.maxNrOfRetries)
      )
    }
  }

  private def getBackoffConfig(appConfig: AppConfig, entityCategory: String, typeName: String): Option[BackoffConfig] = {
    val supervisedEnabled =
      PersistentActorConfigUtil
        .getSupervisedEnabled(appConfig, defaultValue = false, entityCategory, typeName)
    if (supervisedEnabled) {
      val minBackoffSeconds =
        PersistentActorConfigUtil
          .getBackoffMinSeconds(appConfig, 3, entityCategory, typeName) //TODO: finalize number
      val maxBackoffSeconds =
        PersistentActorConfigUtil
          .getBackoffMaxSeconds(appConfig, 100, entityCategory, typeName) //TODO: finalize number
      val randomFactor =
        PersistentActorConfigUtil
          .getBackoffRandomFactor(appConfig, 0.2, entityCategory, typeName) //TODO: finalize number
      val maxNrOfRetries =
        PersistentActorConfigUtil
          .getBackoffMaxNrOfRetries(appConfig, 10, entityCategory, typeName) //TODO: finalize number

      Some(BackoffConfig(minBackoffSeconds, maxBackoffSeconds, randomFactor, maxNrOfRetries))

    } else None
  }
}

case class BackoffConfig(minBackoffSeconds: Int, maxBackOffSeconds: Int, randomFactor: Double, maxNrOfRetries: Int)