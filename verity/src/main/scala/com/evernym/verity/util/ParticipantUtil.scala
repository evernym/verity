package com.evernym.verity.util

import com.evernym.verity.util2.AgentId
import com.evernym.verity.protocol.engine.{DID, ParticipantId}

object ParticipantUtil {

  //TODO: shall this function be moved into protocol package ?
  //because this function takes a parameter of type AgentId,
  // it wasn't sure if protocol is aware about anything like that?
  def participantId(did: DID, agentIdOpt: Option[AgentId]): ParticipantId = {
    val agentId = agentIdOpt.map(aid => s"/$aid").getOrElse("")
    s"$did$agentId"
  }


  def agentId(participantId: ParticipantId): AgentId = participantId.split("/").last

  def DID(participantId: ParticipantId): String = participantId.split("/").head

}
