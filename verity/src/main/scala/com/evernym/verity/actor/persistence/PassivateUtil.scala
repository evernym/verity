package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.RECEIVE_TIMEOUT_SECONDS
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.typesafe.config.ConfigException
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object PassivateUtil {

  private val logger: Logger = getLoggerByName("PassivateUtil")

  /**
   * calculates receive timeout based on given inputs
   *
   * @param appConfig
   * @param defaultReceiveTimeoutInSeconds
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return receive timeout
   */
  def getReceiveTimeout(appConfig: AppConfig,
                        defaultReceiveTimeoutInSeconds: Int,
                        entityCategory: String,
                        entityName: String,
                        entityId: String): Duration = {

    val entityIdReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$entityName.$entityId.$RECEIVE_TIMEOUT_SECONDS", appConfig)
    val entityNameReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$entityName.$RECEIVE_TIMEOUT_SECONDS", appConfig)
    val categoryReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$RECEIVE_TIMEOUT_SECONDS", appConfig)

    val timeout = entityIdReceiveTimeout.getOrElse(
      entityNameReceiveTimeout.getOrElse(
        categoryReceiveTimeout.getOrElse(
          defaultReceiveTimeoutInSeconds
        )
      )
    )
    if (timeout > 0) timeout.seconds else Duration.Undefined
  }

  private def safeGetAppConfigIntOption(key: String, appConfig: AppConfig): Option[Int] =
    try {
      appConfig.getConfigIntOption(key)
    } catch {
      case e: ConfigException =>
        logger.warn(s"exception during getting key: $key from config: $e")
        None
    }
}
