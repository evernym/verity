package com.evernym.verity.config

import com.evernym.verity.config.CommonConfig.{AGENT_AUTHENTICATION_ENABLED, AGENT_AUTHENTICATION_KEYS}
import com.evernym.verity.protocol.engine.{DID, VerKey}

object AgentAuthKeyUtil {

  /**
   * returns configured authorized keys for given domainId (if this feature is enabled)
   * @param config config object
   * @param domainId domainId (self relationship DID, which is provided during agent provisioning)
   * @return set of configured authorized keys
   */
  def keysForSelfRelDID(config: AppConfig, domainId: DID): Set[VerKey] = {
    if (config.getConfigBooleanReq(s"$AGENT_AUTHENTICATION_ENABLED")) {
      config.getConfigSetOfStringOption(s"$AGENT_AUTHENTICATION_KEYS.$domainId").getOrElse(Set.empty)
    } else Set.empty
  }

}
