package com.evernym.verity.actor

import akka.persistence.typed.scaladsl.RetentionCriteria
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.typesafe.config.Config

object RetentionCriteriaBuilder {

  def build(config: Config,
            snapshotConfigPath: String,
            defaultSnapAfterEveryEvent: Int,
            defaultKeepSnapshots: Int,
            defaultDeleteEventsOnSnapshot: Boolean): RetentionCriteria = {

    val crh = ConfigReadHelper(config)

    val afterEveryEvents: Int = crh.getIntOption(s"$snapshotConfigPath.after-every-events")
      .getOrElse(defaultSnapAfterEveryEvent)

    val keepSnapshots: Int = crh.getIntOption(s"$snapshotConfigPath.keep-snapshots")
      .getOrElse(defaultKeepSnapshots)

    val deleteEventsSnapshot: Boolean = crh.getBooleanOption(s"$snapshotConfigPath.delete-events-on-snapshots")
      .getOrElse(defaultDeleteEventsOnSnapshot)

    val retentionCriteria = RetentionCriteria.snapshotEvery(numberOfEvents = afterEveryEvents,
      keepNSnapshots = keepSnapshots)

    if (deleteEventsSnapshot)
      retentionCriteria.withDeleteEventsOnSnapshot
    else
      retentionCriteria
  }
}
