package com.evernym.verity.agentmsg.tokenizer

import com.evernym.verity.util2.Base64Encoded
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.protocol.engine.Nonce
import com.evernym.verity.util.TimeUtil.IsoDateTime

case class GetToken(`@type`: String, sponseeId: String, sponsorId: String, pushId: ComMethodDetail)
case class SendToken(`@type`: String,
                     `~thread`: Thread,
                     sponseeId: String,
                     sponsorId: String,
                     nonce: Nonce,
                     timestamp: IsoDateTime,
                     sig: Base64Encoded,
                     sponsorVerKey: VerKeyStr)
