package com.evernym.verity.actor.persistence.object_code_mapper

import com.evernym.verity.actor.{ItemManagerState, ResourceUsageState}
import scalapb.GeneratedMessageCompanion

object DefaultStateCodeMapper extends ObjectCodeMapperBase {

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> ResourceUsageState,
    2 -> ItemManagerState
    //TODO:
    // In SnapshotterExt, as long as 'stateTransformer' points to 'legacyStateTransformer'
    // any 'new state' object, corresponding proto buf entry should be added here
    // But, as soon as 'stateTransformer' points to 'persistenceTransformer'
    // then onwards, we shouldn't add any new entries here and rather they should be added
    // to 'DefaultObjectMapper'
  )
}
