package com.evernym.verity.protocol.engine.external_api_access

import com.evernym.verity.actor.wallet.TheirKeyStored
import com.evernym.verity.ledger.LedgerRequest
import com.evernym.verity.protocol.engine.{DID, ParticipantId, VerKey}
import com.evernym.verity.util.Base64Util

import scala.concurrent.{Future, Promise}
import scala.util.Try

trait WalletAccess
  extends AnonCredRequests {

  import WalletAccess._

  def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[(DID, VerKey)] => Unit): Unit

  def verKey(forDID: DID)(handler: Try[VerKey] => Unit): Unit

  def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
          (handler: Try[SignatureResult] => Unit): Unit

  /**
    * Protocols often do not know the verkey associated with another participants. A protocol should not have to
    * retrieve verkey information if it is not explicitly needed for that protocol. The signer=ParticipantId allows for
    * a protocol to not know explicitly about another participant's verkey and still verify. This is done by implicitly
    * (by the engine) extracting verkey information from the participant id.
    */
  def verify(signer: ParticipantId,
             msg: Array[Byte],
             sig: Array[Byte],
             verKeyUsed: Option[VerKey],
             signType: SignType = SIGN_ED25519_SHA512_SINGLE
            )(handler: Try[Boolean] => Unit): Unit

  /**
    * This is only used when verifying a signature signed by someone who is not a participant of the protocol (i.e. no participantId).
    * This verkey needs to be explicitly given from a protocol/control message. A protocol shouldn't have to retrieve
    * a verkey to use this.
    */
  def verify(msg: Array[Byte],
             sig: Array[Byte],
             verKeyUsed: VerKey,
             signType: SignType
            )(handler: Try[Boolean] => Unit): Unit



  def storeTheirDid(did: DID, verKey: VerKey)(handler: Try[TheirKeyStored] => Unit): Unit

  def signRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit

  def multiSignRequest(submitterDID: DID, request: String)(handler: Try[LedgerRequest] => Unit): Unit
}

object WalletAccess {
  type KeyType = String
  type SignType = String
  val KEY_ED25519: KeyType = "ed25519"
  val SIGN_ED25519_SHA512_SINGLE: SignType = "spec/signature/1.0/ed25519Sha512_single"
  // TODO: Decide if following belong here or at a broader level
  type PackedMsg = Array[Byte]
}

case class InvalidSignType(message: String) extends Exception(message)
case class NoWalletFound(message: String)   extends Exception(message)

case class SignatureResult(signature: Array[Byte], verKey: VerKey) {
  def toBase64: String = Base64Util.getBase64Encoded(signature)
  def toBase64UrlEncoded: String = Base64Util.getBase64UrlEncoded(signature)
}