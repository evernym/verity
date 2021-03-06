package com.evernym.verity

import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.wallet_api.WalletAPI
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

  /**
   * contains information to be used during accessing (which will require opening the wallet too) the wallet
   * all of this information is needed during accessing libindy wallet
   *
   * @param walletId wallet identifier
   */
  case class WalletAPIParam(walletId: String)

  case class AgentWalletAPI(walletAPI: WalletAPI, walletId: String) {
    def walletAPIParam: WalletAPIParam = WalletAPIParam(walletId)
  }

  case class GetVerKeyByDIDParam(did: DID, getKeyFromPool: Boolean)

  object KeyParam {
    def fromVerKey(verKey: String): KeyParam = KeyParam(Left(verKey))
    def fromDID(did: DID, getKeyFromPool: Boolean = false): KeyParam = KeyParam(Right(GetVerKeyByDIDParam(did, getKeyFromPool)))
  }
  case class KeyParam(verKeyParam: Either[VerKey, GetVerKeyByDIDParam])

  case class SealParam(keyParam: KeyParam)

  case class EncryptParam(recipKeyParams: Set[KeyParam], senderKeyParam: Option[KeyParam])

  //response
  case class LedgerResponse(resp: Any)

  trait WalletExt {
    def wallet: Wallet
  }
}
