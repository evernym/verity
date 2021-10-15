package com.evernym.verity.testkit.mock.agent

import com.evernym.verity.actor.testkit.{AgentDIDDetail, CommonSpecUtil}
import com.evernym.verity.actor.wallet.{StoreTheirKey, TheirKeyStored}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.base.RemoteAgentAndAgencyIdentity
import com.evernym.verity.testkit.AgentWithMsgHelper
import com.evernym.verity.util2.UrlParam

import scala.concurrent.ExecutionContext

/**
 * a mock cloud agent
 * @param agencyEndpoint
 * @param appConfig
 * @param myDIDDetail
 */
class MockCloudAgent(override val executionContext: ExecutionContext,
                     override val agencyEndpoint: UrlParam,
                     override val appConfig: AppConfig,
                     override val myDIDDetail: AgentDIDDetail = CommonSpecUtil.generateNewAgentDIDDetail())
  extends AgentWithMsgHelper {

  override def initSpecific(): Unit = {}
  var remoteAgentAndAgencyIdentityOpt: Option[RemoteAgentAndAgencyIdentity] = None

  def setupRemoteAgentAndAgencyIdentity(raaad: RemoteAgentAndAgencyIdentity): Unit = {
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(raaad.agentDID, raaad.agentVerKey))
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(raaad.agencyDID, raaad.agencyVerKey, ignoreIfAlreadyExists=true))
    remoteAgentAndAgencyIdentityOpt = Option(raaad)
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}