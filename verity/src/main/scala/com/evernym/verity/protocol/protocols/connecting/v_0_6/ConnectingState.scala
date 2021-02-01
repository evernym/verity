package com.evernym.verity.protocol.protocols.connecting.v_0_6

import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.{MY_PUBLIC_DID, MY_SELF_REL_DID}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{ConnectingStateBase, HasPairwiseConnection}
import com.evernym.verity.util.OptionUtil.blankOption

//TODO This state is mutable, and state objects in protocols should be immutable.
case class ConnectingState(appConfig: AppConfig, agentMsgTransformer: AgentMsgTransformer)
  extends ConnectingStateBase[ConnectingState] {

  def myPublicDIDOpt: Option[DID] = parameters.paramValue(MY_PUBLIC_DID).flatMap(blankOption)
  def mySelfRelDIDOpt: Option[DID] = getOwnerDidOpt
  def myPairwiseDIDOpt: Option[DID] = agentDetail.map(_.forDID)
  def thisAgentVerKey: Option[VerKey] = thisAgentVerKeyOpt

  class State extends HasPairwiseConnection
  type StateType = State
  val state = new State


  var sourceId: Option[String] = None
  //TODO why not use OptAgentDetail and the associated helper methods (that seem to be repeated below)
  var agentDetail: Option[AgentDetail] = None
  var thisAgentVerKeyOpt: Option[VerKey] = None
  private def getOwnerDidOpt: Option[DID] = {
    Set(
      //if this protocol is launched from agency agent actors then this param would be empty else it should be non empty
      parameters.paramValue(MY_SELF_REL_DID),
      agentDetail.map(_.forDID)
    ).filter(v => v.isDefined && v.exists(_.trim.nonEmpty)).head
  }
}


