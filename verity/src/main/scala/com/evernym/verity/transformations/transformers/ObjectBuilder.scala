package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.persistence.customDeserializer.record_agent_activity.LegacyAgentActivityRecordedEventDeserializer
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase

object ObjectBuilder {

  /**
   * This can happen when a persisted event/state has been refactored such that
   * it can't directly (without custom handling) deserialize to it's latest object.
   *
   * This is a recovery mechanism to support such persisted legacy event/state and
   * not meant as a planned solution.
   *
   * We are doing this to compensate for an error we see in the system but we should
   * consider a more wholistic approach when we have the need to
   * upcast an event from an old event to a new event.
   */
  def create(typeCode: Int,
             msgBytes: Array[Byte],
             objectCodeMapper: ObjectCodeMapperBase): Any = {
    typeCode match {
      //LegacyAgentActivityRecorded
      case 201  => LegacyAgentActivityRecordedEventDeserializer.deserialize(msgBytes)
      case _    => objectCodeMapper.objectFromCode(typeCode, msgBytes)
    }
  }

}
