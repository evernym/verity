package com.evernym.verity.util

import com.evernym.verity.vault.WalletExt
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.engine.{DID, VerKey}
import org.hyperledger.indy.sdk.did.Did


object Util extends UtilBase {

  override def getVerKey(did: DID, walletExt: WalletExt, getKeyFromPool: Boolean, poolConnManager: LedgerPoolConnManager): VerKey = {
    //TODO: need to fix this so that it can work directly with LedgerPoolConnManager interface
    // instead of relying on which implementation is provided
    (getKeyFromPool, poolConnManager) match {
      case (true, ilp: IndyLedgerPoolConnManager) =>
        Did.keyForDid(ilp.poolConn_!, walletExt.wallet, did).get
      case _ => Did.keyForLocalDid(walletExt.wallet, did).get
    }
  }

}
