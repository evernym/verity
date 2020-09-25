package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.protocol._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DEAD_DROP_STORE_DATA
import com.evernym.verity.protocol.actor.ProtoMsg
import com.evernym.verity.protocol.engine.Scope.ProtocolScope
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.{Base64Encoded, Signature}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey


// TODO: Fix the inconsistent naming across the protocol: Store, StoreData, Add / Get, GetData, GetDeadDropMsg, Retrieve...
trait DeadDropEvt

/** Segmented State */
trait DeadDropSegmentState

case class DeadDropPayload(address: String, data: Array[Byte]) extends DeadDropSegmentState
case class DeadDropEntry(address: String, data: Base64Encoded) extends DeadDropSegmentState


/** Protocol Messages */
sealed trait DeadDropProtoMsg extends ProtoMsg

case class Add(payload: DeadDropPayload) extends DeadDropProtoMsg  //FIXME today this is sent from Driver
case class Retrieve(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature) extends DeadDropProtoMsg
case class Ack() extends DeadDropProtoMsg
case class Nack() extends DeadDropProtoMsg
case class DeadDropRetrieveResult(entry: Option[DeadDropEntry]) extends DeadDropProtoMsg


/** Control Messages */
sealed trait DeadDropCtrlMsg extends Control with MsgBase

case class Params(params: Parameters) extends DeadDropCtrlMsg
case class Init(params: Parameters) extends DeadDropCtrlMsg

//TODO: refactor this so that we don't have to extend it from 'ActorMessageClass'
case class StoreData(payload: DeadDropPayload) extends DeadDropCtrlMsg with HasMsgType with ActorMessageClass {
  def msgName: String = MSG_TYPE_DEAD_DROP_STORE_DATA
  def msgFamily: MsgFamily = DeadDropMsgFamily
}

case class GetData(recoveryVerKey: VerKey, address: String, locator: String, locatorSignature: Signature) extends DeadDropCtrlMsg

object DeadDropProtoDef
  extends ProtocolDefinition[DeadDropProtocol, Role, DeadDropProtoMsg, DeadDropEvt, DeadDropState, String] {

  val msgFamily: MsgFamily = DeadDropMsgFamily

  override val segmentedStateName = Option("payload")

  override def createInitMsg(params: Parameters) = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(Persister(), Retriever())

  override def scope: ProtocolScope = Scope.Agent

  override def supportedMsgs: ProtoReceive = {
    case _: DeadDropProtoMsg =>
    case _: DeadDropCtrlMsg =>
  }

  def create(ctx: ProtocolContextApi[DeadDropProtocol, Role, DeadDropProtoMsg, DeadDropEvt, DeadDropState, String]): DeadDropProtocol = { // TODO can this be generically implemented in the base class?
    new DeadDropProtocol(ctx)
  }

  override def initialState: DeadDropState = DeadDropState.Uninitialized()

  override def segmentRetrieval[A, B >: DeadDropState, C >: SegmentKey]: (A, B) ?=> C = {
    case (r: Retrieve, _) => r.address
  }
}
