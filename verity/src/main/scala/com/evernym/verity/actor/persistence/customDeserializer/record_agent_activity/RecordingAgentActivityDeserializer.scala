package com.evernym.verity.actor.persistence.customDeserializer.record_agent_activity

import com.evernym.verity.actor.agent.{RecordingAgentActivity, SponsorRel}
import com.evernym.verity.actor.persistence.customDeserializer.BaseDeserializer
import com.evernym.verity.actor.persistent.event_adapters.record_agent_activity.RecordingAgentActivityV0

object RecordingAgentActivityDeserializer
  extends BaseDeserializer {

  override def deserialize(msg: Array[Byte]): Any = {
    try {
      RecordingAgentActivity.parseFrom(msg)
    } catch {
      case e: Exception =>
        logger.debug("RecordingAgentActivity parsing attempt failed for latest event message: " + e.getMessage)
        val v0 = RecordingAgentActivityV0.parseFrom(msg)
        logger.debug(s"RecordingAgentActivity v0 conversion: $v0")
        RecordingAgentActivity(
          v0.domainId,
          v0.timestamp,
          Option(SponsorRel(v0.sponsorId, v0.sponseeId)),
          v0.activityType,
          v0.relId,
          v0.stateKey)
    }
  }
}
