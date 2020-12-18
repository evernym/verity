package com.evernym.verity

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.actor.wallet.{GetVerKey, GetVerKeyOpt}
import com.evernym.verity.protocol.engine.{DID, VerKey}
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

  case class AgentWalletAPI(walletAPI: WalletAPI, walletId: String)

  case class GetVerKeyByDIDParam(did: DID, getKeyFromPool: Boolean)

  case class KeyInfo(verKeyDetail: Either[VerKey, GetVerKeyByDIDParam])

  case class GetVerKeyByKeyInfoParam(keyInfo: KeyInfo) {
    def createGetVerKey: GetVerKey = GetVerKey(keyInfo)
    def createGetVerKeyOpt: GetVerKeyOpt = GetVerKeyOpt(keyInfo)
  }

  case class GetVerKeyOptionByKeyInfoParam(keyInfo: KeyInfo)

  case class SealParam(keyInfo: KeyInfo)

  case class EncryptParam(recipKeys: Set[KeyInfo], senderKey: Option[KeyInfo])

  case class GetOrCreateEncryptionKeyParam(did: DID)

  case class ForceUpdateEncryptionKeyParam(did: DID, key: String)

  //response
  case class ValidLedgerResponse(resp: String)
  case class LedgerResponse(resp: Any)
  case class EncryptionKey(key: String)

  trait WalletExt {
    def wallet: Wallet
  }
}
