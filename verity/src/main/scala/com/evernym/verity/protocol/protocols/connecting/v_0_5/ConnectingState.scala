package com.evernym.verity.protocol.protocols.connecting.v_0_5

import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.{ConnectingStateBase, HasPairwiseConnection}
import com.evernym.verity.util.OptionUtil.blankOption


case class ConnectingState(appConfig: AppConfig, agentMsgTransformer: AgentMsgTransformer)
  extends ConnectingStateBase[ConnectingState] {

  def myPublicDIDOpt: Option[DID] = parameters.paramValue(MY_PUBLIC_DID).flatMap(blankOption)
  def mySelfRelDIDOpt: Option[DID] = parameters.paramValue(MY_SELF_REL_DID)
  def myPairwiseDIDOpt: Option[DID] = parameters.paramValue(MY_PAIRWISE_DID)
  def thisAgentVerKey: Option[VerKey] = parameters.paramValue(THIS_AGENT_VER_KEY)

  class State extends HasPairwiseConnection
  type StateType = State
  val state = new State

}


