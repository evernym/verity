package com.evernym.verity.actor.persistence.recovery

import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.{RecordingAgentActivity, SponsorRel}
import com.evernym.verity.actor.base.{Done, Ping}
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.actor.persistence.recovery.base.{BasePersistentStore, PersistParam, PersistenceIdParam}
import com.evernym.verity.actor.persistent.event_adapters.record_agent_activity.RecordingAgentActivityV0
import com.evernym.verity.constants.ActorNameConstants._
import scalapb.GeneratedMessageCompanion

class ActivityTrackerRecoverySpec
  extends BasePersistentStore {

  def at: agentRegion = agentRegion(entityId, activityTrackerRegionActor)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupActivityTracker()
  }

  "ActivityTracker actor" - {
    "when try to recover with legacy and new events" - {
      "should be able to successfully recovered" in {
        at ! Ping(sendBackConfirmation = true)
        expectMsgType[Done.type]
      }
    }
  }

  def setupActivityTracker(): Unit = {
    addEventsToPersistentStorage(persistenceId, legacyEvents)(PersistParam.withLegacyTransformer(legacyObjectCodeMapper))
    addEventsToPersistentStorage(persistenceId, legacyEvents)(PersistParam(legacyObjectCodeMapper))
    addEventsToPersistentStorage(persistenceId, newEvents)  //will use latest transformer and default object code mapper
  }

  lazy val entityId = "000"
  lazy val persistenceId = PersistenceIdParam(ACTIVITY_TRACKER_REGION_ACTOR_NAME, entityId)

  lazy val legacyEvents = scala.collection.immutable.Seq(
    RecordingAgentActivityV0("domainId", "2021-01-02",
      "sponsorId", "activityType", "relId", "stateKey", "sponseeId"),
  )

  lazy val newEvents = scala.collection.immutable.Seq(
    RecordingAgentActivity("domainId", "2021-01-02",
      Option(SponsorRel("sponsorId", "sponseeId")), "activityType", "relId", "stateKey")
  )

  //this mapping is used to store legacy event which is no more in the current 'DefaultObjectCodeMapper'
  lazy val legacyObjectCodeMapper = new ObjectCodeMapperBase {
    lazy val objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map (
      201 -> RecordingAgentActivityV0
    )
  }
}
