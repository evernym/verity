package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Scope.RelProvisioning
import com.evernym.verity.protocol.engine._

import scala.concurrent.ExecutionContext

object OutOfBandDef extends ProtocolDefinition[OutOfBand, Role, Msg, OutOfBandEvent, State, String] {
  override val msgFamily: MsgFamily = OutOfBandMsgFamily

  override def create(
                       context: ProtocolContextApi[OutOfBand, Role, Msg, OutOfBandEvent, State, String],
                       executionContext: ExecutionContext
                     ): Protocol[OutOfBand, Role, Msg, OutOfBandEvent, State, String] = {
    new OutOfBand(context)
  }

  override def initialState: State = State.Uninitialized()


  override val roles: Set[Role] = Set(Role.Invitee, Role.Inviter)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p)

  override def scope: Scope.ProtocolScope = RelProvisioning
}