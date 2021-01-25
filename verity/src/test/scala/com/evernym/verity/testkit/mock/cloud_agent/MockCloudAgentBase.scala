package com.evernym.verity.testkit.mock.cloud_agent

import com.evernym.verity.actor.wallet.{StoreTheirKey, TheirKeyStored}
import com.evernym.verity.http.base.RemoteAgentAndAgencyIdentity
import com.evernym.verity.testkit.AgentWithMsgHelper

/**
 * mock cloud agent base class (specific classes will extend this one)
 */

trait MockCloudAgentBase extends AgentWithMsgHelper {

  override def initSpecific(): Unit = {}
  var remoteAgentAndAgencyIdentityOpt: Option[RemoteAgentAndAgencyIdentity] = None

  def setupRemoteAgentAndAgencyIdentity(raaad: RemoteAgentAndAgencyIdentity): Unit = {
    walletAPI.executeSync[TheirKeyStored](StoreTheirKey(raaad.agentDID, raaad.agentVerKey))
    walletAPI.executeSync[TheirKeyStored](StoreTheirKey(raaad.agencyDID, raaad.agencyVerKey, ignoreIfAlreadyExists=true))
    remoteAgentAndAgencyIdentityOpt = Option(raaad)
  }

}
