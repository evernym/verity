package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.{DID, Parameters, UNINITIALIZED, VerKey}
import com.evernym.verity.protocol.protocols._

trait ConnectingStateBase[S]
  extends HasPairwiseConnectionState
    with MsgAndDeliveryState
    with HasAppConfig { this: S =>

  var parameters: Parameters = _
  var stateStr: String = UNINITIALIZED
  var agentKeyDlgProof: Option[AgentKeyDlgProof] = None

  def appConfig: AppConfig
  def agentMsgTransformer: AgentMsgTransformer

  final def agencyDIDOpt: Option[DID] = parameters.paramValue(AGENCY_DID)

  def myPublicDIDOpt: Option[DID]
  final def myPublicDIDReq: DID = myPublicDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my public DID not found"))
  )

  def mySelfRelDIDOpt: Option[DID]
  final def mySelfRelDIDReq: DID = mySelfRelDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my self relationship DID not found"))
  )
  def myPairwiseDIDOpt: Option[DID]
  final def myPairwiseDIDReq: DID = myPairwiseDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my pairwise DID not found"))
  )

  def thisAgentVerKey: Option[VerKey]
  final def thisAgentVerKeyReq: DID = thisAgentVerKey.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("this agent ver key not found"))
  )
  final def myAgentKeyDlgProofReq: AgentKeyDlgProof = agentKeyDlgProof.getOrElse(
    throw new BadRequestErrorException(KEY_DELEGATED_PROOF_NOT_FOUND.statusCode))

  final def theirAgentKeyDID: Option[DID] = state.theirAgentKeyDID
  final def theirAgentKeyDIDReq: DID = theirAgentKeyDID.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("their agent key DID not found"))
  )
}


