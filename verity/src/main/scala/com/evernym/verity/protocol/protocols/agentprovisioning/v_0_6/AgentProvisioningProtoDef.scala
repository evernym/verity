package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.metrics.MetricsWriterExtensionImpl
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.{Init, ProtoMsg}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.asyncapi.{AccessNewDid, AccessRight, AccessStoreTheirDiD, AccessVerKey, DEPRECATED_AccessSetupNewWallet}
import com.evernym.verity.protocol.engine.{MsgName, _}
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator}

object AgentProvisioningMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_AGENT_PROVISIONING
  override val version: MsgFamilyVersion = MFV_0_6

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "AgentCreationCompleted" -> classOf[AgentCreationCompleted]
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    MSG_TYPE_CREATE_AGENT -> classOf[CreateAgentReqMsg_MFV_0_6],
    MSG_TYPE_AGENT_CREATED -> classOf[AgentCreatedRespMsg_MFV_0_6]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[AskUserAgentCreator]       -> "ask-user-agent-creator"
  )
}

object AgentProvisioningProtoDef
    extends ProtocolDefinition[AgentProvisioningProtocol,Role,ProtoMsg,Any,State,String] {

  val msgFamily: MsgFamily = AgentProvisioningMsgFamily

  override val roles: Set[Role] = Set.empty

  override lazy val initParamNames: Set[String] = Set(
    AGENT_PROVISIONER_PARTICIPANT_ID,
    THIS_AGENT_WALLET_ID,
    NEW_AGENT_WALLET_ID,
    CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON
  )

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def create(context: ProtocolContextApi[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String]
                      , mw: MetricsWriterExtensionImpl):
  Protocol[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String] =
    new AgentProvisioningProtocol(context)

  override def initialState: State = State.Uninitialized()

  override val requiredAccess: Set[AccessRight] = Set(DEPRECATED_AccessSetupNewWallet, AccessVerKey, AccessNewDid, AccessStoreTheirDiD)
}

case class CreateAgentReqMsg_MFV_0_6(fromDID: DID, fromDIDVerKey: VerKey) extends ProtoMsg


case class AgentCreatedRespMsg_MFV_0_6(withPairwiseDID: DID, withPairwiseDIDVerKey: VerKey) extends ProtoMsg
