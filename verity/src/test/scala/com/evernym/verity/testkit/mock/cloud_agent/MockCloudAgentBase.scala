package com.evernym.verity.testkit.mock.cloud_agent

import com.evernym.verity.actor.wallet.StoreTheirKey
import com.evernym.verity.http.base.RemoteAgentAndAgencyIdentity
import com.evernym.verity.testkit.AgentWithMsgHelper

/**
 * mock cloud agent base class (specific classes will extend this one)
 */

trait MockCloudAgentBase extends AgentWithMsgHelper {

  override def initSpecific(): Unit = {}
  var remoteAgentAndAgencyIdentityOpt: Option[RemoteAgentAndAgencyIdentity] = None

  def setupRemoteAgentAndAgencyIdentity(raaad: RemoteAgentAndAgencyIdentity): Unit = {
    walletAPI.storeTheirKey(StoreTheirKey(raaad.agentDID, raaad.agentVerKey))
    walletAPI.storeTheirKey(StoreTheirKey(raaad.agencyDID, raaad.agencyVerKey, ignoreIfAlreadyExists=true))
    remoteAgentAndAgencyIdentityOpt = Option(raaad)
  }

}
