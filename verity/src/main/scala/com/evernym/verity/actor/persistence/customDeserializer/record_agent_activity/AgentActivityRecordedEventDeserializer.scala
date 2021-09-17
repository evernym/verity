package com.evernym.verity.actor.persistence.customDeserializer.record_agent_activity

import com.evernym.verity.actor.agent.{AgentActivityRecorded, SponsorRel}
import com.evernym.verity.actor.persistence.customDeserializer.BaseDeserializer
import com.evernym.verity.actor.persistent.event_adapters.record_agent_activity.AgentActivityRecordedV0

object AgentActivityRecordedEventDeserializer
  extends BaseDeserializer {

  override def deserialize(msg: Array[Byte]): Any = {
    try {
      AgentActivityRecorded.parseFrom(msg)
    } catch {
      case e: Exception =>
        logger.debug("AgentActivityRecorded parsing attempt failed for an event: " + e.getMessage)
        val v0 = AgentActivityRecordedV0.parseFrom(msg)
        logger.debug(s"AgentActivityRecordedV0 conversion: $v0")
        AgentActivityRecorded(
          v0.domainId,
          v0.timestamp,
          Option(SponsorRel(v0.sponsorId, v0.sponseeId)),
          v0.activityType,
          v0.relId,
          v0.stateKey)
    }
  }
}
