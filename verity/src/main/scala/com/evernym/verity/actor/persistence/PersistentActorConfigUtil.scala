package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.ConfigUtil.{getConfBooleanValue, getConfIntValue}
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.typesafe.config.ConfigException
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.{Duration, _}

object PersistentActorConfigUtil {

  private val logger: Logger = getLoggerByName("PersistentActorConfigUtil")

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
