package com.evernym.verity.actor.persistence.customDeserializer.record_agent_activity

import com.evernym.verity.actor.agent.{LegacyAgentActivityRecorded, SponsorRel}
import com.evernym.verity.actor.persistence.customDeserializer.BaseDeserializer
import com.evernym.verity.actor.persistent.event_adapters.record_agent_activity.LegacyAgentActivityRecordedV0

//a custom deserializer to handle a legacy event which was changed (since it got persisted)
// in a non backward compatible way
object LegacyAgentActivityRecordedEventDeserializer
  extends BaseDeserializer {

  override def deserialize(msg: Array[Byte]): Any = {
    try {
      LegacyAgentActivityRecorded.parseFrom(msg)
    } catch {
      case e: Exception =>
        logger.debug("LegacyAgentActivityRecorded parsing attempt failed for an event: " + e.getMessage)
        val legacyV0 = LegacyAgentActivityRecordedV0.parseFrom(msg)
        logger.debug(s"LegacyAgentActivityRecordedV0 conversion: $legacyV0")
        LegacyAgentActivityRecorded(
          legacyV0.domainId,
          legacyV0.timestamp,
          Option(SponsorRel(legacyV0.sponsorId, legacyV0.sponseeId)),
          legacyV0.activityType,
          legacyV0.relId,
          legacyV0.stateKey)
    }
  }
}
