package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.ParticipantId
import com.evernym.verity.protocol.engine.asyncapi.AsyncResultHandler

trait WalletAsyncOps
  extends AnonCredAsyncOps
  with AsyncResultHandler {

  import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

  def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair): Unit

  def runNewDid(keyType: KeyType = KEY_ED25519): Unit

  def runStoreTheirDid(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false): Unit

  def runVerKey(forDID: DidStr): Unit

  def runVerKeyOpt(forDID: DidStr): Unit

  def runSign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit

  def runSignRequest(submitterDID: DidStr, request: String): Unit

  def runMultiSignRequest(submitterDID: DidStr, request: String): Unit

  def runVerify(signer: ParticipantId,
                msg: Array[Byte],
                sig: Array[Byte],
                verKeyUsed: Option[VerKeyStr] = None,
                signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit

  def runVerify(msg: Array[Byte],
                sig: Array[Byte],
                verKeyUsed: VerKeyStr,
                signType: SignType): Unit
}
