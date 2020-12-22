package com.evernym.verity.http.route_handlers

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.{AgentActorContext, DidPair}
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentDetail, AgencyIdUtil, GetAgencyAgentDetail}
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * provides access to platform
 */
trait PlatformServiceProvider
  extends AgencyIdUtil
  with MsgTraceProvider {

  def appConfig: AppConfig
  def platform: Platform
  def agentActorContext: AgentActorContext = platform.agentActorContext

  lazy val logger: Logger = getLoggerByClass(classOf[PlatformServiceProvider])

  implicit var wap: WalletAPIParam = _

  def getAgencyDidPairFut: Future[DidPair] = {
    AgencyAgent.agencyAgentDetail.map { aad =>
      wap = WalletAPIParam(aad.walletId)
      Future.successful(DidPair(aad.did, aad.verKey))
    }.getOrElse {
      getAgencyDID(agentActorContext.generalCache).flatMap { agencyId =>
        agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(agencyId, GetAgencyAgentDetail)) map {
          case Some(aad: AgencyAgentDetail) =>
            wap = WalletAPIParam(aad.walletId)
            DidPair(aad.did, aad.verKey)
          case _ =>
            throw new RuntimeException("agency agent not yet setup")
        }
      }
    }
  }
}
