package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, TypedMsgLike}
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.msg.Init
import com.evernym.verity.protocol.engine.validate.ValidateHelper.checkRequired
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator}
import com.evernym.verity.util.Util.getNewActorId

object AgentProvisioningMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_AGENT_PROVISIONING
  override val version: MsgFamilyVersion = MFV_0_5

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "PairwiseEndpointCreated" -> classOf[PairwiseEndpointCreated],
    "AgentCreationCompleted"  -> classOf[AgentCreationCompleted]
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    MSG_TYPE_CONNECT          -> classOf[ConnectReqMsg_MFV_0_5],
    MSG_TYPE_SIGN_UP          -> classOf[SignUpReqMsg_MFV_0_5],
    MSG_TYPE_CREATE_AGENT     -> classOf[CreateAgentReqMsg_MFV_0_5],
    MSG_TYPE_CONNECTED        -> classOf[ConnectedRespMsg_MFV_0_5],
    MSG_TYPE_SIGNED_UP        -> classOf[SignedUpRespMsg_MFV_0_5],
    MSG_TYPE_AGENT_CREATED    -> classOf[AgentCreatedRespMsg_MFV_0_5]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[AskAgencyPairwiseCreator]  -> "ask-agency-pairwise-creator",
    classOf[AskUserAgentCreator]       -> "ask-user-agent-creator",
  )

}

object AgentProvisioningProtoDef
  extends ProtocolDefinition[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String] {

  val msgFamily: MsgFamily = AgentProvisioningMsgFamily

  override val roles: Set[Role] = Set.empty

  override lazy val initParamNames: Set[String] = Set(
    AGENT_PROVISIONER_PARTICIPANT_ID,
    THIS_AGENT_WALLET_ID,
    NEW_AGENT_WALLET_ID,
    CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON,
    CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON
  )

  override def createInitMsg(params: Parameters): Control = Init(params)

  override def protocolIdSuffix(typedMsg: TypedMsgLike): Option[String] = {
    typedMsg.msgType.msgName match {
      case MSG_TYPE_CONNECT => Option(getNewActorId)
      case _                => None
    }
  }

  override def create(context: ProtocolContextApi[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String]): Protocol[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String] =
    new AgentProvisioningProtocol(context)

  override def initialState: State = State.Uninitialized()
}

trait ProtoMsg extends MsgBase

case class ConnectReqMsg_MFV_0_5(fromDID: DidStr, fromDIDVerKey: VerKeyStr) extends ProtoMsg {
  override def validate(): Unit = {
    checkRequired("fromDID", fromDID)
    checkRequired("fromDIDVerKey", fromDIDVerKey)
  }

  def didPair: DidPair = DidPair(fromDID, fromDIDVerKey)
}

case class ConnectedRespMsg_MFV_0_5(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr) extends ProtoMsg

case class SignUpReqMsg_MFV_0_5() extends ProtoMsg

case class SignedUpRespMsg_MFV_0_5() extends ProtoMsg

case class CreateAgentReqMsg_MFV_0_5(fromDID: Option[DidStr]=None,
                                     fromDIDVerKey: Option[VerKeyStr]=None) extends ProtoMsg

case class AgentCreatedRespMsg_MFV_0_5(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr) extends ProtoMsg

