package com.evernym.verity.testkit.mock.edge_agent

import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.UrlParam

/**
 * a mock consumer edge agent
 * @param agencyEndpoint cas endpoint (hosting consumer's cloud agent)
 * @param appConfig
 * @param myDIDDetail
 */
class MockConsumerEdgeAgent(override val agencyEndpoint: UrlParam,
                            override val appConfig: AppConfig,
                            override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail())
  extends MockEdgeAgent