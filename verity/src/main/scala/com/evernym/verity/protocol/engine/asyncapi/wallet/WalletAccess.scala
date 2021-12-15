package com.evernym.verity.protocol.engine.asyncapi.wallet

import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.protocol.engine.ParticipantId
import com.evernym.verity.util.Base64Util

import scala.util.Try

trait WalletAccess
  extends AnonCredRequests {

  import WalletAccess._

  def DEPRECATED_setupNewWallet(walletId: String, ownerDidPair: DidPair)(handler: Try[DeprecatedWalletSetupResult] => Unit): Unit

  def newDid(keyType: KeyType = KEY_ED25519)(handler: Try[NewKeyResult] => Unit): Unit

  def verKey(forDID: DidStr)(handler: Try[VerKeyResult] => Unit): Unit

  def verKeyOpt(forDID: DidStr)(handler: Try[VerKeyOptResult] => Unit): Unit

  def sign(msg: Array[Byte], signType: SignType = SIGN_ED25519_SHA512_SINGLE)
          (handler: Try[SignedMsgResult] => Unit): Unit

  /**
   * Protocols often do not know the verKey associated with another participants.
   * A protocol should not have to retrieve verKey information if it is not explicitly
   * needed for that protocol. The signer=ParticipantId allows for a protocol to not know
   * explicitly about another participant's verKey and still verify. This is done by implicitly
   * (by the engine) extracting verKey information from the participant id.
   */
  def verify(signer: ParticipantId,
             msg: Array[Byte],
             sig: Array[Byte],
             verKeyUsed: Option[VerKeyStr],
             signType: SignType = SIGN_ED25519_SHA512_SINGLE
            )(handler: Try[VerifiedSigResult] => Unit): Unit

  /**
    * This is only used when verifying a signature signed by someone who is not a participant of the protocol (i.e. no participantId).
    * This verKey needs to be explicitly given from a protocol/control message. A protocol shouldn't have to retrieve
    * a verKey to use this.
    */
  def verify(msg: Array[Byte],
             sig: Array[Byte],
             verKeyUsed: VerKeyStr,
             signType: SignType
            )(handler: Try[VerifiedSigResult] => Unit): Unit

  def storeTheirDid(did: DidStr, verKey: VerKeyStr, ignoreIfAlreadyExists: Boolean = false)(handler: Try[TheirKeyStoredResult] => Unit): Unit

  def signRequest(submitterDID: DidStr, request: String)(handler: Try[LedgerRequestResult] => Unit): Unit

  def multiSignRequest(submitterDID: DidStr, request: String)(handler: Try[LedgerRequestResult] => Unit): Unit
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

case class SignatureResult(signature: Array[Byte], verKey: VerKeyStr) {
  def toBase64: String = Base64Util.getBase64Encoded(signature)
  def toBase64UrlEncoded: String = Base64Util.getBase64UrlEncoded(signature)
}

case class DeprecatedWalletSetupResult(ownerDidPair: DidPair, agentKey: NewKeyResult)
case class NewKeyResult(did: DidStr, verKey: VerKeyStr) {
  def didPair: DidPair = DidPair(did, verKey)
}
case class VerKeyResult(verKey: VerKeyStr)
case class VerKeyOptResult(verKey: Option[VerKeyStr])
case class SignedMsgResult(msg: Array[Byte], fromVerKey: VerKeyStr) {
  def signatureResult: SignatureResult = SignatureResult(msg, fromVerKey)
}
case class VerifiedSigResult(verified: Boolean)
case class TheirKeyStoredResult(did: DidStr, verKey: VerKeyStr) {
  def didPair: DidPair = DidPair(did, verKey)
}

case class SchemaCreatedResult(schemaId: String, schemaJson: String)

case class CredDefCreatedResult(credDefId: String, credDefJson: String)

case class CredOfferCreatedResult(offer: String)
case class CredReqCreatedResult(credReqJson: String, credReqMetadataJson: String)
case class CredCreatedResult(cred: String)
case class CredStoredResult(cred: String)
case class CredForProofResult(cred: String)

case class ProofCreatedResult(proof: String)
case class ProofVerificationResult(result: Boolean)

case class TransactionAuthorAgreement(version: String, digest: String, mechanism: String, timeOfAcceptance: String)
case class LedgerRequestResult(req: String, needsSigning: Boolean=true, taa: Option[TransactionAuthorAgreement]=None) {
  def prepared(newRequest: String): LedgerRequestResult = this.copy(req=newRequest)
}