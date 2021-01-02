package com.evernym.verity.testkit.mock.cloud_agent

import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.UrlParam

/**
 * a mock cloud agent
 * @param agencyEndpoint
 * @param appConfig
 * @param myDIDDetail
 */
class MockCloudAgent(override val agencyEndpoint: UrlParam,
                     override val appConfig: AppConfig,
                     override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail())
  extends MockCloudAgentBase