package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.{SenderDID, SenderOrder}

/**
  * Had to add duplicate fields (with just difference in case styles, camel case vs snake case etc)
  * Those are temporary changes done for backward compatibility
  * @param thid
  * @param pthid
  * @param received_orders
  * @param sender_order
  * @param receivedOrders
  * @param senderOrder
  */
case class MsgThread(thid: Option[ThreadId]=None,
                     pthid: Option[ThreadId]=None,
                     private val received_orders: Option[Map[SenderDID, SenderOrder]]=None,
                     private val sender_order: Option[Int]=None,
                     private val receivedOrders: Option[Map[SenderDID, SenderOrder]]=None,
                     private val senderOrder: Option[Int]=None) {

  if (sender_order.nonEmpty && senderOrder.nonEmpty) {
    throw new RuntimeException("'sender_order' and 'senderOrder' both can't be set")
  }
  if (received_orders.nonEmpty && receivedOrders.nonEmpty) {
    throw new RuntimeException("'received_orders' and 'receivedOrders' both can't be set")
  }

  def getSenderOrder: Int = (sender_order ++ senderOrder).headOption.getOrElse(0)
  def getReceivedOrders: Option[Map[SenderDID, SenderOrder]] = {
    (received_orders ++ receivedOrders).headOption
  }
}
