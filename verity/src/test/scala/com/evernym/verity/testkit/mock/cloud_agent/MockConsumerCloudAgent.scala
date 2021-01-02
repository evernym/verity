package com.evernym.verity.testkit.mock.cloud_agent

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.UrlParam

/**
 * mock consumer cloud agent
 * @param system
 * @param appConfig
 * @param myDIDDetail
 */
class MockConsumerCloudAgent(val system: ActorSystem,
                             override val appConfig: AppConfig,
                             override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail())
  extends MockCloudAgentBase {
  override val agencyEndpoint = UrlParam("localhost:9001/agency/msg")
}