package com.evernym.verity.protocol.protocols

import com.evernym.verity.did.VerKey
import com.evernym.verity.protocol.engine.{MsgFamily, MsgTypeStr}
import com.evernym.verity.util2.Base64Encoded

object CommonProtoTypes {
  case class Thread(thid: String, seqnum: Int)

  case class Timing(expires_time: Option[String] = None,
                    in_time: Option[String] = None,
                    out_time: Option[String] = None,
                    stale_time: Option[String] = None,
                    delay_milli: Option[Int] = None,
                    wait_until_time: Option[String] = None
                   )

  case class Localization(locale: Option[String] = None)

  case class SigBlock(signature: Base64Encoded,
                      sig_data: Base64Encoded,
                      signers: Seq[VerKey],
                      `@type`: Option[MsgTypeStr]
                              = Some(MsgFamily.typeStrFromMsgType(MsgFamily.COMMUNITY_QUALIFIER, "signature", "1.0", "ed25519Sha512_single"))  //"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/signature/1.0/ed25519Sha512_single")
                      )

  // Aries RFC has replaced `signers` with `signer` and now only 1 verkey is contained.
  // TODO: Reconcile with above SigBlock.
  case class SigBlockCommunity(signature: Base64Encoded,
                      sig_data: Base64Encoded,
                      signer: VerKey,
                      `@type`: Option[MsgTypeStr]
                      = Some(MsgFamily.typeStrFromMsgType(MsgFamily.COMMUNITY_QUALIFIER, "signature", "1.0", "ed25519Sha512_single"))
                     )
}
