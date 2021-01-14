package com.evernym.verity.actor.persistence.recovery

import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.{RecordingAgentActivity, SponsorRel}
import com.evernym.verity.actor.base.{Done, Ping}
import com.evernym.verity.actor.persistence.recovery.base.{BasePersistentStore, PersistParam}
import com.evernym.verity.constants.ActorNameConstants._

class ActivityTrackerRecoverySpec
  extends BasePersistentStore {

  def at: agentRegion = agentRegion(activityTrackerEntityId, activityTrackerRegionActor)

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
    addEventsToPersistentStorage(activityTrackerPersistenceId, legacyEvents)(PersistParam.legacy(activityTrackerEntityId))
    addEventsToPersistentStorage(activityTrackerPersistenceId, newEvents)(PersistParam.default(activityTrackerEntityId))
  }

  lazy val activityTrackerEntityId = "000"
  lazy val activityTrackerPersistenceId = s"$ACTIVITY_TRACKER_REGION_ACTOR_NAME-$activityTrackerEntityId"

  lazy val legacyEvents = scala.collection.immutable.Seq(
    RecordingAgentActivity("domainId", "2021-01-01",
      Option(SponsorRel("sponsorId", "sponseeId")), "activityType", "relId", "stateKey")
  )

  lazy val newEvents = scala.collection.immutable.Seq(
    RecordingAgentActivity("domainId", "2021-01-02",
      Option(SponsorRel("sponsorId", "sponseeId")), "activityType", "relId", "stateKey")
  )
}
