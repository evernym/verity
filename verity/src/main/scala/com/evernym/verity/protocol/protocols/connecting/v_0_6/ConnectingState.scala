package com.evernym.verity.protocol.protocols.connecting.v_0_6

import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.constants.InitParamConstants.{MY_PUBLIC_DID, MY_SELF_REL_DID}
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
  def mySelfRelDIDOpt: Option[DidStr] = getOwnerDidOpt
  def myPairwiseDIDOpt: Option[DidStr] = agentDetail.map(_.forDID)
  def thisAgentVerKey: Option[VerKeyStr] = thisAgentVerKeyOpt

  class State extends HasPairwiseConnection
  type StateType = State
  val state = new State


  var sourceId: Option[String] = None
  //TODO why not use OptAgentDetail and the associated helper methods (that seem to be repeated below)
  var agentDetail: Option[AgentDetail] = None
  var thisAgentVerKeyOpt: Option[VerKeyStr] = None
  private def getOwnerDidOpt: Option[DidStr] = {
    Set(
      //if this protocol is launched from agency agent actors then this param would be empty else it should be non empty
      parameters.paramValue(MY_SELF_REL_DID),
      agentDetail.map(_.forDID)
    ).filter(v => v.isDefined && v.exists(_.trim.nonEmpty)).head
  }
}


