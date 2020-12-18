package com.evernym.verity

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.vault.WalletUtil.{createWalletAccessParam, generateWalletKey}
import org.hyperledger.indy.sdk.did.{Did, DidJSONParameters}
import org.hyperledger.indy.sdk.wallet.Wallet

package object vault {

  class BaseWalletException extends RuntimeException {
    val message: String = ""
    override def getMessage: String = message
  }

  case class WalletInvalidState(override val message: String = "") extends BaseWalletException
  case class WalletDoesNotExist(override val message: String = "") extends BaseWalletException
  case class WalletAlreadyExist(override val message: String = "") extends BaseWalletException

  case class WalletNotOpened(override val message: String = "") extends BaseWalletException
  case class WalletAlreadyOpened(override val message: String = "") extends BaseWalletException

  case class WalletNotClosed(override val message: String = "") extends BaseWalletException
  case class WalletNotDeleted(override val message: String = "") extends BaseWalletException
  case class WalletUnhandledError(override val message: String = "") extends BaseWalletException

  object WalletAccessParam {
    def apply(wd: WalletDetail, appConfig: AppConfig, closeAfterUse: Boolean): WalletAccessParam = {
      apply(wd.seed, wd.walletAPI, wd.walletConfig, appConfig, closeAfterUse)
    }

    def apply(seed: String, walletAPI: WalletAPI,
              walletConfig: WalletConfig, appConfig: AppConfig,
              closeAfterUse: Boolean): WalletAccessParam = {
      val key = generateWalletKey(seed, walletAPI, appConfig)
      createWalletAccessParam(seed, key, walletConfig, appConfig).copy(closeAfterUse = closeAfterUse)
    }
  }
  /**
   * contains information to be used during accessing (which will require opening the wallet too) the wallet
   * all of this information is needed during accessing libindy wallet
   *
   * @param walletName wallet name (a unique name to identify a wallet)
   * @param encryptionKey wallet encryption key
   * @param walletConfig wallet config (storage config etc)
   * @param closeAfterUse whether wallet should be closed after being used/accessed for given task (function call)
   */
  case class WalletAccessParam(walletName: String,
                               encryptionKey: String,
                               walletConfig: WalletConfig,
                               closeAfterUse: Boolean = true) {
    // TODO we should not concate string before hashing, should use safeMultiHash
    def getUniqueKey: String = HashUtil.hash(SHA256)(walletName + encryptionKey).hex
  }

  case class WalletDetail(walletAPI: WalletAPI, walletConfig: WalletConfig, seed: String)

  case class CreateNewKeyParam(DID: Option[DID] = None, seed: Option[String] = None) {
    override def toString: String = {
      val redacted = seed.map(_ => "redacted")
      s"CreateNewKeyParam($DID, $redacted)"
    }
  }

  case class StoreTheirKeyParam(theirDID: DID, theirDIDVerKey: VerKey)

  case class GetVerKeyByDIDParam(did: DID, getKeyFromPool: Boolean)

  case class KeyInfo(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam])

  case class GetVerKeyByKeyInfoParam(keyInfo: KeyInfo)

  case class GetVerKeyOptionByKeyInfoParam(keyInfo: KeyInfo)

  case class SealParam(keyInfo: KeyInfo)

  case class EncryptParam(recipKeys: Set[KeyInfo], senderKey: Option[KeyInfo])

  case class VerifySigByVerKeyParam(verKey: VerKey, challenge: Array[Byte], signature: Array[Byte])

  case class GetOrCreateEncryptionKeyParam(did: DID)

  case class ForceUpdateEncryptionKeyParam(did: DID, key: String)

  case class SubmitReqParam(reqDetail: LedgerRequest, submitterDetail: Submitter)

  case class SignMsgParam(keyInfo: KeyInfo, msg: Array[Byte])

  case class VerifySigByKeyInfoParam(keyInfo: KeyInfo, challenge: Array[Byte], signature: Array[Byte])

  case class UnpackedMsg(msg: Any, recipient_verkey: VerKey, sender_verkey: Option[VerKey])


  //response
  case class NewKeyCreated(did: DID, verKey: VerKey)  extends ActorMessageClass
  case class TheirKeyCreated(did: DID, verKey: VerKey)
  case class ValidLedgerResponse(resp: String)
  case class LedgerResponse(resp: Any)
  case class VerifResult(verified: Boolean)
  case class EncryptionKey(key: String)

  trait WalletExt {
    def wallet: Wallet

    def createNewKey(idOpt: Option[String] = None, seedOption: Option[String] = None): NewKeyCreated = {
      val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(idOpt.orNull, seedOption.orNull, null, null)
      val didResult = Did.createAndStoreMyDid(wallet, DIDJson.toJson).get
      NewKeyCreated(didResult.getDid, didResult.getVerkey)
    }

    def storeTheirDID(did: DID, verKey: VerKey): TheirKeyCreated = {
      val DIDJson = s"""{\"did\":\"$did\",\"verkey\":\"$verKey\"}"""
      Did.storeTheirDid(wallet, DIDJson).get
      TheirKeyCreated(did, verKey)
    }

  }
}
