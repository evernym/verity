package com.evernym.verity.actor.agent.msghandler

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.base.BaseNonPersistentActor
import com.evernym.verity.config.AppConfig

class AgentMsgProcessor(val appConfig: AppConfig)
  extends BaseNonPersistentActor {

  override def receiveCmd: Receive = {
    case ppm: ProcessPackedMsg =>
  }

  def handlePackedMsg(ppm: ProcessPackedMsg): Unit = {

  }
}

case class ProcessPackedMsg(pm: PackedMsgParam) extends ActorMessageClass