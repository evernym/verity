package com.evernym.verity.protocol.protocols.connecting.v_0_5

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.protocols.connecting.common.{ConnectingStateBase, HasPairwiseConnection}
import com.evernym.verity.util.OptionUtil.blankOption


//NOTE: don't remove the 'isInitialized' field, it is used
// to decide if the protocol state is initial state or something different.
// this check is done in ActorProtocolContainer
//TODO This state is mutable, and state objects in protocols should be immutable.
case class ConnectingState(isInitialized: Boolean=false)
  extends ConnectingStateBase[ConnectingState] {

  def myPublicDIDOpt: Option[DidStr] = parameters.paramValue(MY_PUBLIC_DID).flatMap(blankOption)
  def mySelfRelDIDOpt: Option[DidStr] = parameters.paramValue(MY_SELF_REL_DID)
  def myPairwiseDIDOpt: Option[DidStr] = parameters.paramValue(MY_PAIRWISE_DID)
  def thisAgentVerKey: Option[VerKeyStr] = parameters.paramValue(THIS_AGENT_VER_KEY)

  class State extends HasPairwiseConnection
  type StateType = State
  val state = new State

}


