package com.evernym.verity.util

import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgThread
import com.evernym.verity.protocol.engine.{MsgType, VerKey}

case class RestAuthContext(verKey: VerKey, signature: String)
case class RestMsgContext(msgType: MsgType, auth: RestAuthContext, thread: Option[MsgThread],
                          reqMsgContext: ReqMsgContext, sync: Boolean = false) {

  def clientIpAddress: Option[String] = reqMsgContext.clientIpAddress
}

