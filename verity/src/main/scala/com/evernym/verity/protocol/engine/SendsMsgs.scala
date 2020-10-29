package com.evernym.verity.protocol.engine

import scala.concurrent.Future

/**
  * Services that sends messages
  *
  */
trait SendsMsgs {

  def prepare(env: Envelope1[Any]): ProtocolOutgoingMsg

  def send(pmsg: ProtocolOutgoingMsg): Unit

  //TODO how a message is sent should not be a concern of the caller
  def sendSMS(toPhoneNumber: String, msg: String): Future[String]
}

abstract class SendsMsgsForContainer[M](container: ProtocolContainer[_,_,M,_,_,_]) extends SendsMsgs {

  def prepare(env: Envelope1[Any]): ProtocolOutgoingMsg = {
    val msgIdReq = env.msgId getOrElse {
      throw new RuntimeException("msgId required while sending protocol outgoing message")
    }
    val threadIdReq = env.threadId getOrElse {
      throw new RuntimeException("threadId required while sending protocol outgoing message")
    }
    //TODO add checks here to make sure the from is correct, and that the recipient is actually an address that can be sent to
    ProtocolOutgoingMsg(env.msg, env.to, env.frm, msgIdReq, threadIdReq, container.pinstId, container.definition)
  }
}
