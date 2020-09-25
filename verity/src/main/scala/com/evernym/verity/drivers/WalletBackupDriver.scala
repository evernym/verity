package com.evernym.verity.drivers

import com.evernym.verity.cache.Cache
import com.evernym.verity.protocol.protocols.deaddrop.{DeadDropPayload, StoreData}
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupMsgFamily.{ProvideRecoveryDetails, Restored}

class WalletBackupDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  def generalCache: Cache = cp.generalCache

  override def signal[A]: SignalHandler[A] = {

    case sig @ SignalEnvelope(ab: ProvideRecoveryDetails, _, _, _, _) =>
      registerDeadDropPayload(ab)
      processSignalMsg(sig)
    case SignalEnvelope(Restored(_), _, _, _, _) => None
  }

  def registerDeadDropPayload(ab: ProvideRecoveryDetails): Unit = {
    //TODO: is it ok for this driver to know about a message type of another protocol?
    val msg = StoreData(DeadDropPayload(ab.params.ddAddress, ab.params.cloudAddress))
    sendToAgencyAgent(msg)
  }
}
