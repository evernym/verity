package com.evernym.verity.testkit.mock.cloud_agent

import com.evernym.verity.http.base.RemoteAgentAndAgencyIdentity
import com.evernym.verity.testkit.{AgentWithMsgHelper, BasicSpecBase}
import com.evernym.verity.vault.StoreTheirKeyParam

/**
 * mock cloud agent base class (specific classes will extend this one)
 */

trait MockCloudAgentBase extends AgentWithMsgHelper {

  override def initSpecific(): Unit = {}
  var remoteAgentAndAgencyIdentityOpt: Option[RemoteAgentAndAgencyIdentity] = None

  def setupRemoteAgentAndAgencyIdentity(raaad: RemoteAgentAndAgencyIdentity): Unit = {
    walletAPI.storeTheirKey(StoreTheirKeyParam(raaad.agentDID, raaad.agentVerKey))
    walletAPI.storeTheirKey(StoreTheirKeyParam(raaad.agencyDID, raaad.agencyVerKey), ignoreIfAlreadyExists=true)
    remoteAgentAndAgencyIdentityOpt = Option(raaad)
  }

}
