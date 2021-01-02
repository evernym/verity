package com.evernym.verity.testkit.mock.agency_admin

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.UrlParam

/**
 * mock agency admin agent
 * @param system
 * @param agencyEndpoint
 * @param appConfig
 * @param myDIDDetail
 */
class MockAgencyAdmin(val system: ActorSystem,
                      override val agencyEndpoint: UrlParam,
                      override val appConfig: AppConfig,
                      override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail())
  extends MockEdgeAgent  {
}