package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.typesafe.config.ConfigException
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.{Duration, _}

object PersistentActorConfigUtil {

  private val logger: Logger = getLoggerByName("PersistentActorConfigUtil")

  /**
   * reads 'receive timeout' configuration
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

    val confValue = getConfIntValue(appConfig, entityCategory, entityName, entityId, RECEIVE_TIMEOUT_SECONDS)
    val timeout = confValue.getOrElse(defaultReceiveTimeoutInSeconds)
    if (timeout > 0) timeout.seconds else Duration.Undefined
  }

  /**
   * reads 'snapshot after n event' configuration
   *
   * @param appConfig
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return snapshot after n event value
   */
  def getSnapshotAfterNEvents(appConfig: AppConfig,
                              entityCategory: String,
                              entityName: String,
                              entityId: String): Option[Int] = {
    getConfIntValue(appConfig, entityCategory, entityName, entityId, SNAPSHOT_AFTER_N_EVENTS)
  }

  /**
   * reads 'snapshots to keep' configuration
   *
   * @param appConfig
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return how many snapshots to be kept
   */
  def getKeepNSnapshots(appConfig: AppConfig,
                        entityCategory: String,
                        entityName: String,
                        entityId: String): Option[Int] = {
    getConfIntValue(appConfig, entityCategory, entityName, entityId, KEEP_N_SNAPSHOTS)
  }

  /**
   * reads 'delete events on snapshots' configuration
   *
   * @param appConfig
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return 'delete events on snapshots' value
   */
  def getDeleteEventsOnSnapshots(appConfig: AppConfig,
                                 entityCategory: String,
                                 entityName: String,
                                 entityId: String): Option[Boolean] = {
    getConfBooleanValue(appConfig, entityCategory, entityName, entityId, DELETE_EVENTS_ON_SNAPSHOTS)
  }


  //TODO: there are some code duplication, need to find a better way to fix it

  private def getConfIntValue(appConfig: AppConfig,
                              entityCategory: String,
                              entityName: String,
                              entityId: String,
                              confName: String): Option[Int] = {
    val entityIdReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$entityName.$entityId.$confName", appConfig)
    val entityNameReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$entityName.$confName", appConfig)
    val categoryReceiveTimeout: Option[Int] =
      safeGetAppConfigIntOption(s"$entityCategory.$confName", appConfig)

    entityIdReceiveTimeout orElse entityNameReceiveTimeout orElse categoryReceiveTimeout
  }

  private def getConfBooleanValue(appConfig: AppConfig,
                                  entityCategory: String,
                                  entityName: String,
                                  entityId: String,
                                  confName: String): Option[Boolean] = {
    val entityIdReceiveTimeout: Option[Boolean] =
      safeGetAppConfigBooleanOption(s"$entityCategory.$entityName.$entityId.$confName", appConfig)
    val entityNameReceiveTimeout: Option[Boolean] =
      safeGetAppConfigBooleanOption(s"$entityCategory.$entityName.$confName", appConfig)
    val categoryReceiveTimeout: Option[Boolean] =
      safeGetAppConfigBooleanOption(s"$entityCategory.$confName", appConfig)

    entityIdReceiveTimeout orElse entityNameReceiveTimeout orElse categoryReceiveTimeout
  }

  private def safeGetAppConfigIntOption(key: String, appConfig: AppConfig): Option[Int] =
    try {
      appConfig.getConfigIntOption(key)
    } catch {
      case e: ConfigException =>
        logger.warn(s"exception during getting key: $key from config: $e")
        None
    }

  private def safeGetAppConfigBooleanOption(key: String, appConfig: AppConfig): Option[Boolean] =
    try {
      appConfig.getConfigBooleanOption(key)
    } catch {
      case e: ConfigException =>
        logger.warn(s"exception during getting key: $key from config: $e")
        None
    }
}
