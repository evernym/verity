package com.evernym.verity.protocol.protocols

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.MsgTypeStr
import com.evernym.verity.util2.Base64Encoded

object CommonProtoTypes {

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
                      signers: Seq[VerKeyStr],
                      `@type`: Option[MsgTypeStr] = Some(MsgFamily.typeStrFromMsgType(
                        MsgFamily.COMMUNITY_QUALIFIER,
                        "signature",
                        "1.0",
                        "ed25519Sha512_single")
                        )
                      )

  // Aries RFC has replaced `signers` with `signer` and now only 1 verkey is contained.
  // TODO: Reconcile with above SigBlock.
  case class SigBlockCommunity(signature: Base64Encoded,
                               sig_data: Base64Encoded,
                               signer: VerKeyStr,
                               `@type`: Option[MsgTypeStr] = Some(MsgFamily.typeStrFromMsgType(
                                 MsgFamily.COMMUNITY_QUALIFIER,
                                 "signature",
                                 "1.0",
                                 "ed25519Sha512_single")
                               )
                              )
}
