package com.evernym.verity.util

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.MsgType
import com.evernym.verity.did.didcomm.v1.Thread

case class RestAuthContext(verKey: VerKeyStr, signature: String)
case class RestMsgContext(msgType: MsgType,
                          auth: RestAuthContext,
                          thread: Option[Thread],
                          reqMsgContext: ReqMsgContext, sync: Boolean = false) {

  def clientIpAddress: Option[String] = reqMsgContext.clientIpAddress
}

