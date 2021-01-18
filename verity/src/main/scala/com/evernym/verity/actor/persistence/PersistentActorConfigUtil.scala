package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil.{getConfBooleanValue, getConfIntValue, getConfDoubleValue}

object PersistentActorConfigUtil {

  /**
   * reads 'supervised-enabled' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @return supervised-enabled or not
   */
  def getSupervisedEnabled(appConfig: AppConfig,
                           defaultValue: Boolean,
                           entityCategory: String,
                           entityName: String): Boolean = {
    val confValue = getConfBooleanValue(appConfig, entityCategory, SUPERVISED_STRATEGY_ENABLED, Option(entityName), None)
    confValue.getOrElse(defaultValue)
  }

  /**
   * reads 'backoff-min-seconds' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @return backoff-min-seconds
   */
  def getBackoffMinSeconds(appConfig: AppConfig,
                           defaultValue: Int,
                           entityCategory: String,
                           entityName: String): Int = {
    val confValue = getConfIntValue(appConfig, entityCategory, BACKOFF_SUPERVISED_STRATEGY_MIN_SECONDS, Option(entityName), None)
    confValue.getOrElse(defaultValue)
  }

  /**
   * reads 'backoff-max-seconds' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @return backoff-max-seconds
   */
  def getBackoffMaxSeconds(appConfig: AppConfig,
                           defaultValue: Int,
                           entityCategory: String,
                           entityName: String): Int = {
    val confValue = getConfIntValue(appConfig, entityCategory, BACKOFF_SUPERVISED_STRATEGY_MAX_SECONDS, Option(entityName), None)
    confValue.getOrElse(defaultValue)
  }

  /**
   * reads 'backoff-random-factor' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @return backoff-random-factor
   */
  def getBackoffRandomFactor(appConfig: AppConfig,
                             defaultValue: Double,
                             entityCategory: String,
                             entityName: String): Double = {
    val confValue = getConfDoubleValue(appConfig, entityCategory, BACKOFF_SUPERVISED_STRATEGY_RANDOM_FACTOR, Option(entityName), None)
    confValue.getOrElse(defaultValue)
  }

  /**
   * reads 'recover-from-snapshots' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return recover-from-snapshots
   */
  def getRecoverFromSnapshot(appConfig: AppConfig,
                             defaultValue: Boolean,
                             entityCategory: String,
                             entityName: String,
                             entityId: String): Boolean = {

    val confValue = getConfBooleanValue(appConfig, entityCategory, RECOVER_FROM_SNAPSHOT, Option(entityName), Option(entityId))
    confValue.getOrElse(defaultValue)
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
    getConfIntValue(appConfig, entityCategory, SNAPSHOT_AFTER_N_EVENTS, Option(entityName), Option(entityId))
  }

  /**
   * reads 'snapshots to keep' configuration
   *
   * @param appConfig
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return snapshots to keep
   */
  def getKeepNSnapshots(appConfig: AppConfig,
                        entityCategory: String,
                        entityName: String,
                        entityId: String): Option[Int] = {
    getConfIntValue(appConfig, entityCategory, KEEP_N_SNAPSHOTS, Option(entityName), Option(entityId))
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
    getConfBooleanValue(appConfig, entityCategory, DELETE_EVENTS_ON_SNAPSHOTS, Option(entityName), Option(entityId))
  }
}
