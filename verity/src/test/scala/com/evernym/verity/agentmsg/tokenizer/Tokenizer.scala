package com.evernym.verity.agentmsg.tokenizer

import com.evernym.verity.Base64Encoded
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgThread
import com.evernym.verity.protocol.engine.{Nonce, VerKey}
import com.evernym.verity.util.TimeUtil.IsoDateTime

case class GetToken(`@type`: String, sponseeId: String, sponsorId: String, pushId: ComMethodDetail)
case class SendToken(`@type`: String,
                     `~thread`: MsgThread,
                     sponseeId: String,
                     sponsorId: String,
                     nonce: Nonce,
                     timestamp: IsoDateTime,
                     sig: Base64Encoded,
                     sponsorVerKey: VerKey)
