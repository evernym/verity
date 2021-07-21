package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.{ThreadContextDetail, TypeFormat}
import com.evernym.verity.constants.Constants.UNKNOWN_SENDER_PARTICIPANT_ID
import com.evernym.verity.protocol.engine.{MsgId, ProtoDef}

import java.util.UUID

object AgentMsgBuilder {

  def createAgentMsg(msg: Any,
                     protoDef: ProtoDef,
                     threadContextDetail: ThreadContextDetail,
                     msgTypeFormat: Option[TypeFormat] = None): AgentJsonMsg = {

    def getNewMsgId: MsgId = UUID.randomUUID().toString

    val (msgId, mtf, msgOrders) = {
      val mId = if (threadContextDetail.msgOrders.exists(_.senderOrder == 0)
        && threadContextDetail.msgOrders.exists(_.receivedOrders.isEmpty)) {
        //this is temporary workaround to solve an issue between how
        // thread id is determined by libvcx (and may be by other third parties) vs verity/agency
        // here, we are basically checking if this msg is 'first' protocol msg and in that case
        // the @id of the msg is assigned the thread id itself
        threadContextDetail.threadId
      } else {
        getNewMsgId
      }
      (mId, msgTypeFormat.getOrElse(threadContextDetail.msgTypeFormat), threadContextDetail.msgOrders)
    }

    //need to find better way to handle this
    //during connections protocol, when first message 'request' is received from other side,
    //that participant is unknown and hence it is stored as 'unknown_sender_participant_id' in the thread context
    //and when it responds with 'response' message, it just adds that in thread object
    //but for recipient it may look unfamiliar and for now filtering it.
    val updatedMsgOrders = msgOrders.map { pmd =>
      pmd.copy(receivedOrders = pmd.receivedOrders.filter(_._1 != UNKNOWN_SENDER_PARTICIPANT_ID))
    }
    buildAgentMsg(msg, msgId, threadContextDetail.threadId, protoDef, mtf, updatedMsgOrders)
  }
}
