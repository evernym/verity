package com.evernym.verity.actor

import com.evernym.verity.agentmsg.msgcodec.TypeFormat
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, MsgPackVersion, ParticipantId, ThreadId}

package object agent {


  case class ReceivedMsgOrder(from: ParticipantId, order: Int)

  /**
   *
   * @param threadId thread id
   * @param msgPackVersion msg pack version (message pack = 0.5 or indy pack = 1.0)
   * @param msgTypeFormat msg type format, see 'TypeFormat' for more detail
   * @param usesLegacyGenMsgWrapper this is to support legacy msg wrapping structure
   *                                where the actual message is wrapped into a general message wrapper
   * @param usesLegacyBundledMsgWrapper this is to support legacy agency messages which needs to be wrapped
   *                                    into bundled messages
   * @param protoMsgOrderDetail protocol msg order details (sender order, received order)
   */
  case class ThreadContextDetail(threadId: ThreadId,
                                 msgPackVersion: MsgPackVersion,
                                 msgTypeFormat: TypeFormat,
                                 usesLegacyGenMsgWrapper: Boolean = false,
                                 usesLegacyBundledMsgWrapper: Boolean=false,
                                 protoMsgOrderDetail: ProtoMsgOrderDetail=ProtoMsgOrderDetail())

  type MsgOrder = Int
  case class ProtoMsgOrderDetail(senderOrder: Int = -1, receivedOrders: Map[ParticipantId, MsgOrder] = Map.empty)

  /*
    For now, this is only used for testing purposes
   */
  case class GetPinstId(msg: Any, threadId: ThreadId=DEFAULT_THREAD_ID)

  case class ActorEndpointDetail(regionTypeName: String, entityId: String)

  type AttrName = String
  type AttrValue = String
}
