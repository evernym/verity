package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.Init
import com.evernym.verity.protocol.engine._
import AgentProvisioningMsgFamily._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AccessVerify}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOneDomain

object AgentProvisioningDefinition
  extends ProtocolDefinition[AgentProvisioning,Role,Msg,Any,AgentProvisioningState,String] {

  val msgFamily: MsgFamily = AgentProvisioningMsgFamily

  override def segmentStoreStrategy: Option[SegmentStoreStrategy] = Some(OneToOneDomain)

  override val roles: Set[Role] = Set(Requester, Provisioner)

  override lazy val initParamNames: Set[String] = Set(SELF_ID, OTHER_ID, DATA_RETENTION_POLICY)

  override val requiredAccess: Set[AccessRight] = Set(AccessVerify)

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def create(context: ProtocolContextApi[AgentProvisioning, Role, Msg, Any, AgentProvisioningState, String]
                      , mw: MetricsWriter):
  Protocol[AgentProvisioning, Role, Msg, Any, AgentProvisioningState, String] = {
    new AgentProvisioning(context)
  }

  override def initialState: AgentProvisioningState = State.Uninitialized()
}
