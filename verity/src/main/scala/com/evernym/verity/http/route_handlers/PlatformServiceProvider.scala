package com.evernym.verity.http.route_handlers

import akka.pattern.ask
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.{AgentActorContext, DidPair}
import com.evernym.verity.actor.agent.agency.{AgencyAgentDetail, AgencyIdUtil, GetAgencyAgentDetail}
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.appStateManager.{AppStateEvent, AppStateRequest, AppStateUpdateAPI}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeTracker
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * provides access to platform objects
 * to be able to communicate/send messages to agency agent actor
 */
trait PlatformServiceProvider
  extends AgencyIdUtil
    with MsgRespTimeTracker
    with HasActorResponseTimeout {

  def appConfig: AppConfig
  def platform: Platform
  def agentActorContext: AgentActorContext = platform.agentActorContext

  def publishAppStateEvent (event: AppStateEvent): Unit = {
    AppStateUpdateAPI(agentActorContext.system).publishEvent(event)
  }

  def askAppStateManager(cmd: AppStateRequest): Future[Any] = {
    platform.appStateManager ? cmd
  }

  lazy val logger: Logger = getLoggerByClass(classOf[PlatformServiceProvider])

  protected var agencyDIDPair: DidPair = _
  implicit var wap: WalletAPIParam = _

  def getAgencyDidPairFut: Future[DidPair] = {
    Option(wap).map { _ =>
      Future.successful(agencyDIDPair)
    }.getOrElse {
      getAgencyDID(agentActorContext.generalCache).flatMap { agencyId =>
        agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(agencyId, GetAgencyAgentDetail)) map {
          case aad: AgencyAgentDetail =>
            wap = WalletAPIParam(aad.walletId)
            agencyDIDPair = DidPair(aad.did, aad.verKey)
            agencyDIDPair
          case _ =>
            throw new RuntimeException("agency agent not yet setup")
        }
      }
    }
  }
}
