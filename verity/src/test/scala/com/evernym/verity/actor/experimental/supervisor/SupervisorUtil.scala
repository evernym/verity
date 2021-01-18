package com.evernym.verity.actor.experimental.supervisor

import akka.actor.Props
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import com.evernym.verity.actor.persistence.PersistentActorConfigUtil
import com.evernym.verity.config.AppConfig

import scala.concurrent.duration._

object SupervisorUtil {

  val SUPERVISED_ACTOR_NAME = "supervised"

  def backoffSupervisorActorProps(appConfig: AppConfig, entityCategory: String, typeName: String, childProps: Props): Option[Props] = {
    val supervisedEnabled =
      PersistentActorConfigUtil
      .getSupervisedEnabled(appConfig, defaultValue=false, entityCategory, typeName)
    if (supervisedEnabled) {
      val minBackoffSeconds =
        PersistentActorConfigUtil
          .getBackoffMinSeconds(appConfig, 3, entityCategory, typeName)
      val maxBackoffSeconds =
        PersistentActorConfigUtil
          .getBackoffMaxSeconds(appConfig, 100, entityCategory, typeName)
      val randomFactor =
        PersistentActorConfigUtil
          .getBackoffRandomFactor(appConfig, 0.2, entityCategory, typeName)

      //this will create instance of 'BackoffOnStopSupervisor' actor
      Option(BackoffSupervisor.props(
        BackoffOpts
          .onFailure(
            childProps,
            childName = SUPERVISED_ACTOR_NAME,
            minBackoff = minBackoffSeconds.seconds,
            maxBackoff = maxBackoffSeconds.seconds,
            randomFactor = randomFactor)
          ))
    } else None
  }
}
