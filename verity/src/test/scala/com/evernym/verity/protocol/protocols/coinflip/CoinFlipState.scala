package com.evernym.verity.protocol.protocols.coinflip

import com.evernym.verity.protocol.engine.ParticipantId

sealed trait CoinFlipState extends TypeState[StateData, CoinFlipState, Role] {
  def addIds(myId: String, theirId: String): StateData = data.copy(myId = Some(myId), theirId = Some(theirId))

  def isMyRole(role: Role) = data.myRole.contains(role)
  def isTheirRole(role: Role) = data.theirRole.contains(role)

  def addValue(role: Role, newValue: String): StateData = {
    val d = data
    role match {
      case _: Caller => d.copy(callerData = d.callerData.copy(value=Some(newValue)))
      case _: Chooser => d.copy(chooserData = d.chooserData.copy(value=Some(newValue)))
    }
  }

  def addCommitment(role: Role, newValue: String): StateData = {
    val d = data
    role match {
      case _: Caller => d.copy(callerData = d.callerData.copy(commitment=Some(newValue)))
      case _: Chooser => d.copy(chooserData = d.chooserData.copy(commitment=Some(newValue)))
    }
  }

  def addResult(role: Role, isWinner: Boolean): StateData = {
    val d = data
    role match {
      case _: Caller => d.copy(callerData = d.callerData.copy(winner=Some(isWinner)))
      case _: Chooser => d.copy(chooserData = d.chooserData.copy(winner=Some(isWinner)))
    }
  }

  def addMyRole(role: Role): StateData = {
    data.copy(myRole = Some(role))
  }

  def addTheirRole(role: Role): StateData = {
    data.copy(theirRole = Some(role))
  }

  def getTheirRoleData(): RoleData = {
    data.theirRole match {
      case Some(role) => getRoleData(role)
      case None => ???
    }
  }

  def getMyRoleData(): RoleData = {
    data.myRole match {
      case Some(role) => getRoleData(role)
      case None => ???
    }
  }

  def getRoleData(role: Role): RoleData = {
    role match {
      case _: Caller  => data.callerData
      case _: Chooser => data.chooserData
    }
  }

  def isComplete: Boolean ={
    this match {
      case _: Winner => true
      case _: Loser  => true
      case _         => false
    }
  }
}

case class New(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq(Committed.getClass, Error.getClass)
}

case class Committed(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq(Revealed.getClass, Error.getClass)
}

case class Revealed(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq(Winner.getClass, Loser.getClass, Error.getClass)
}

case class Winner(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq()
}

case class Loser(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq()
}

case class Error(data: StateData) extends CoinFlipState {
  override def transitions: Seq[Class[_]] = Seq()
}

case class StateData(myRole: Option[Role],
                     myId: Option[ParticipantId],
                     theirId: Option[ParticipantId],
                     theirRole: Option[Role],
                     callerData: RoleData,
                     chooserData: RoleData)

case class RoleData(value: Option[String]=None,
                    commitment: Option[String]=None,
                    winner: Option[Boolean]=None)

object CoinFlipState {
  def create(role: Option[Role]=None): New = {
    New(StateData(
      role,
      None,
      None,
      None,
      RoleData(),
      RoleData())
    )
  }
}
