package com.evernym.verity.did.didcomm.v1

trait ThreadBase {
  def sender_order: Option[Int]
  def received_orders: Map[String, Int]

  def senderOrder: Option[Int] = sender_order
  def senderOrderReq: Int = sender_order.getOrElse(0)
  def receivedOrders: Option[Map[String, Int]] = Option(received_orders)
}

object ThreadBase {
  // This should not be needed but is used as we move proto buf objects out of actor.agent package
  def convert(t: com.evernym.verity.actor.agent.Thread): Thread = {
    Thread(t.thid, t.pthid, t.sender_order, t.received_orders)
  }
  def convertOpt(t: Option[com.evernym.verity.actor.agent.Thread]): Option[Thread] = {
    t.map{ t => Thread(t.thid, t.pthid, t.sender_order, t.received_orders)}

  }
}