package com.evernym.verity.protocol.engine

trait RosterLike[R] {
  /**
    * collection of all protocol participants
    */
  def participants: Vector[ParticipantId]

  /**
    * mapping of all of the roles assignments to the protocol participants' indices
    */
  def assignments: Map[R, ParticipantIndex]

  /**
    * index of the protocol owner in the participants collection
    */
  def selfIndex: Option[ParticipantIndex]


  def selfIndex_! : ParticipantIndex

  def roleForIdx(idx: ParticipantIndex): Option[R]
  def selfRole: Option[R]
  def participantIndex(id: ParticipantId): Option[ParticipantIndex]
  def roleForId(id: ParticipantId): Option[R]

  /**
    * The participant id of the protocol owner
    * @return Some of the Participant ID, None if it cannot be determined
    */
  def selfId: Option[ParticipantId]

/**
  * The participant id of the protocol owner
  * throws an exception if participant id cannot be determined
  */
  def selfId_! : ParticipantId

}

case class Roster[R](participants: Vector[ParticipantId] = Vector[ParticipantId](),
                     assignments: Map[R, ParticipantIndex] = Map[R, ParticipantIndex](),
                     selfIndex: Option[ParticipantIndex] = None
                           ) extends RosterLike[R] {

  //TODO should we create a new bi-directional map type to make this more convenient? A bimap?
  def roleForIdx(idx: ParticipantIndex): Option[R] = assignments.find(_._2 == idx).map(_._1)

  def selfRole: Option[R] = selfIndex.flatMap(roleForIdx)

  def selfRole_! : R = selfRole.getOrElse(throw new RuntimeException("self role unknown"))

  def participantIdForRole(role: R): Option[ParticipantId] = {
    assignments.get(role).flatMap(participants.lift)
  }

  def participantIdForRole_!(role: R): ParticipantId =
    participantIdForRole(role).getOrElse(throw new RuntimeException(s"no known participant id for role: $role"))


  def participantIndex(id: ParticipantId): Option[ParticipantIndex] = {
    participants.indexOf(id) match {
      case -1 => None
      case x => Some(x)
    }
  }

  def participantIndex_!(id: ParticipantId): ParticipantIndex = participantIndex(id)
    .getOrElse(throw new RuntimeException("participant index not found"))

  def selfId: Option[ParticipantId] = selfIndex.map(participants)

  def selfId_! : ParticipantId = selfId.getOrElse(throw new RuntimeException("self is unknown"))

  def selfIndex_! : ParticipantIndex = selfIndex.getOrElse(throw new RuntimeException("self is unknown"))

  def roleForId(id: ParticipantId): Option[R] = participantIndex(id).flatMap(roleForIdx)

  def hasOther: Boolean = {
    selfIndex match {
      case Some(_) => participants.length > 1
      case None => participants.nonEmpty
    }
  }
  /**
    * In two participant protocols, returns the other participant.
    * @param index the participant index that is the basis for other; what is returned is the index that is *not* the supplied index; optional, defaults to self
    * @return
    */
  def otherIndex(index: ParticipantIndex = -1): ParticipantIndex = {
    val _idx = if (index == -1) selfIndex_! else index
    if (participants.size > 3 || participants.size < 2) throw new RuntimeException("unable to determine other")
    1 - _idx // _idx wil be either 0 or 1, so this effectively 'flips the bit'
  }

  def otherId(index: ParticipantIndex = -1): ParticipantId = participants(otherIndex(index))

  def otherId(id: ParticipantId): ParticipantId = otherId(participantIndex_!(id))

  /**
   * Returns a new Roster which is a copy of the current Roster with a new participant with index as `id`
   * if `id` is not already present. Can optionally set selfIndex to `id` if `isSelf` is passed as true
   * @param
   * @return
   */
  def withParticipant(id: ParticipantId, isSelf: Boolean = false): Roster[R] = {
    def newRole(idx: ParticipantIndex, p: Vector[ParticipantId]): Roster[R] =
      if (isSelf)
        copy( selfIndex = Some(idx), participants = p)
      else
        copy( participants = p )

    if (participants.contains(id))
      newRole(participants.indexOf(id), participants)
    else
      newRole(participants.length, participants :+ id)
  }

  def withAssignmentById(assignment: (R, ParticipantId)*): Roster[R] = {
    val temp = assignment.map(a => (a._1, participantIndex_!(a._2)))
    val temp2:Map[R, ParticipantIndex] = assignments ++ temp
    copy(assignments = temp2)
  }

  def withSelfAssignment(assignment: R): Roster[R] = {
    copy(assignments = assignments ++ Set(assignment -> selfIndex_!))
  }

  def withAssignment(assignment: (R, ParticipantIndex)*): Roster[R] = {
    copy(assignments = assignments ++ assignment)
  }

  def selfSender = Sender(selfId, selfIndex, selfRole)

  def senderFromId(id: ParticipantId) = Sender(Option(id), Option(id).flatMap(participantIndex), roleForId(id))

  def changeSelfId(newId: ParticipantId): Roster[R] = {
    selfIndex match {
      case Some(idx) => copy(participants = participants.updated(idx, newId))
      case None => withParticipant(newId, true)
    }
  }

  def changeOtherId(newId: ParticipantId) = {
    if (hasOther) {
      copy(participants = participants.updated(otherIndex(), newId))
    }
    else {
      withParticipant(newId)
    }
  }
}
