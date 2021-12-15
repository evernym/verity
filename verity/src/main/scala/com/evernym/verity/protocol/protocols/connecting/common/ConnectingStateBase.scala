package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.{Parameters, UNINITIALIZED}

trait ConnectingStateBase[S]
  extends HasPairwiseConnectionState
    with ConnectionMsgAndDeliveryState { this: S =>

  var parameters: Parameters = _
  var stateStr: String = UNINITIALIZED
  var agentKeyDlgProof: Option[AgentKeyDlgProof] = None

  final def agencyDIDOpt: Option[DidStr] = parameters.paramValue(AGENCY_DID)

  def myPublicDIDOpt: Option[DidStr]
  final def myPublicDIDReq: DidStr = myPublicDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my public DID not found"))
  )

  def mySelfRelDIDOpt: Option[DidStr]
  final def mySelfRelDIDReq: DidStr = mySelfRelDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my self relationship DID not found"))
  )
  def myPairwiseDIDOpt: Option[DidStr]
  final def myPairwiseDIDReq: DidStr = myPairwiseDIDOpt.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("my pairwise DID not found"))
  )

  def thisAgentVerKey: Option[VerKeyStr]
  final def thisAgentVerKeyReq: DidStr = thisAgentVerKey.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("this agent ver key not found"))
  )
  final def myAgentKeyDlgProofReq: AgentKeyDlgProof = agentKeyDlgProof.getOrElse(
    throw new BadRequestErrorException(KEY_DELEGATED_PROOF_NOT_FOUND.statusCode))

  final def theirAgentKeyDID: Option[DidStr] = state.theirAgentKeyDID
  final def theirAgentKeyDIDReq: DidStr = theirAgentKeyDID.getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("their agent key DID not found"))
  )
}


