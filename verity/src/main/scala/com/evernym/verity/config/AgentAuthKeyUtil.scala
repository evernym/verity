package com.evernym.verity.config

import com.evernym.verity.config.ConfigConstants.{AGENT_AUTHENTICATION_ENABLED, AGENT_AUTHENTICATION_KEYS}
import com.evernym.verity.did.{DidStr, VerKeyStr}

object AgentAuthKeyUtil {

  /**
   * returns configured authorized keys for given domainId (if this feature is enabled)
   * @param config config object
   * @param domainId domainId (self relationship DID, which is provided during agent provisioning)
   * @return set of configured authorized keys
   */
  def keysForSelfRelDID(config: AppConfig, domainId: DidStr): Set[VerKeyStr] = {
    if (config.getBooleanReq(s"$AGENT_AUTHENTICATION_ENABLED")) {
      config.getStringSetOption(s"$AGENT_AUTHENTICATION_KEYS.$domainId").getOrElse(Set.empty)
    } else Set.empty
  }

}
