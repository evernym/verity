package com.evernym.verity.util

import com.evernym.verity.protocol.engine.{MsgType, VerKey}
import com.evernym.verity.actor.agent.Thread

case class RestAuthContext(verKey: VerKey, signature: String)
case class RestMsgContext(msgType: MsgType,
                          auth: RestAuthContext,
                          thread: Option[Thread],
                          reqMsgContext: ReqMsgContext, sync: Boolean = false) {

  def clientIpAddress: Option[String] = reqMsgContext.clientIpAddress
}

