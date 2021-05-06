package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}

trait WalletAsyncOps extends AnonCredAsyncOps {

  import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess._

  def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair): Unit

  def runNewDid(keyType: KeyType = KEY_ED25519): Unit

  def runStoreTheirDid(did: DID, verKey: VerKey, ignoreIfAlreadyExists: Boolean = false): Unit

  def runVerKey(forDID: DID): Unit

  def runVerKeyOpt(forDID: DID): Unit

  def runSign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit

  def runSignRequest(submitterDID: DID, request: String): Unit

  def runMultiSignRequest(submitterDID: DID, request: String): Unit

  def runVerify(signer: ParticipantId,
                msg: Array[Byte],
                sig: Array[Byte],
                verKeyUsed: Option[VerKey] = None,
                signType: SignType = SIGN_ED25519_SHA512_SINGLE): Unit

  def runVerify(msg: Array[Byte],
                sig: Array[Byte],
                verKeyUsed: VerKey,
                signType: SignType): Unit
}
