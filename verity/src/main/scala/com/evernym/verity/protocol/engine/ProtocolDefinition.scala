package com.evernym.verity.protocol.engine

import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, TypedMsgLike}
import com.evernym.verity.protocol.engine.Scope.ProtocolScope
import com.evernym.verity.protocol.engine.asyncapi.AccessRight
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateProtoDef

/**
 *
 * @tparam P Protocol type
 * @tparam R Role type
 * @tparam M Message type
 * @tparam E Event type
 * @tparam S State type
 * @tparam I Message Recipient Identifier Type
 */
trait ProtocolDefinition[P, R, M, E, S, I] extends SegmentedStateProtoDef[S] {

  def msgFamily: MsgFamily

  //TODO: once all protocol defs have implemented `inputs`, we may remove `supportedMsgs`
  // until then though, all protocol definition will have to provide implementation of this method
  def supportedMsgs: ProtoReceive = Map.empty

  def scope: ProtocolScope = {
    // TODO log warning that protocol has not defined its scope
    Scope.Adhoc
  }

  val roles: Set[R] = Set.empty

  val requiredAccess: Set[AccessRight] = Set.empty

  def create(context: ProtocolContextApi[P, R, M, E, S, I]): Protocol[P, R, M, E, S, I]

  def initialState: S

  def initParamNames: Set[ParameterName] = Set.empty

  def createInitMsg(params: Parameters): Any = {
    if (initParamNames.isEmpty) {
      throw new RuntimeException("createInitMsg was called with no initParamNames defined")
    } else {
      throw new RuntimeException("initParamNames are defined, yet createInitMsg was not overridden; createInitMsg must be defined if initParamNames is")
    }
  }

  def protocolIdSuffix(typedMsg: TypedMsgLike): Option[String] = None

  final lazy val protoRef: ProtoRef = {
    ProtoRef(msgFamily.name, msgFamily.version)
  }

  override def toString: ParticipantId = msgFamily.toString
}

object Scope {
  sealed trait ProtocolScope

  case object Agent extends ProtocolScope
  case object Relationship extends ProtocolScope
  case object Adhoc extends ProtocolScope

  case object RelProvisioning extends ProtocolScope
}
