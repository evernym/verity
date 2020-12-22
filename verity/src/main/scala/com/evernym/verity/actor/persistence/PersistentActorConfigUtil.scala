package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil.{getConfBooleanValue, getConfIntValue}

object PersistentActorConfigUtil {

  /**
   * reads 'recover-from-snapshots' configuration
   *
   * @param appConfig
   * @param defaultValue
   * @param entityCategory
   * @param entityName
   * @param entityId
   * @return receive timeout
   */
  def getRecoverFromSnapshot(appConfig: AppConfig,
                             defaultValue: Boolean,
                             entityCategory: String,
                             entityName: String,
                             entityId: String): Boolean = {

    val confValue = getConfBooleanValue(appConfig, entityCategory, entityName, entityId, RECOVER_FROM_SNAPSHOT)
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
}
