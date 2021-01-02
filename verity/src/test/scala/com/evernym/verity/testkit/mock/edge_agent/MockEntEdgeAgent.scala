package com.evernym.verity.testkit.mock.edge_agent

import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.UrlParam

/**
 * mock enterprise edge agent
 * @param agencyEndpoint
 * @param appConfig
 * @param myDIDDetail
 */
class MockEntEdgeAgent(override val agencyEndpoint: UrlParam,
                       override val appConfig: AppConfig,
                       override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail()
                      )
  extends MockEdgeAgent {

  var inviteUrl: String = _
}
