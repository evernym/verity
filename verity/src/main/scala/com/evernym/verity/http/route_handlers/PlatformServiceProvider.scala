package com.evernym.verity.http.route_handlers

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.agency.AgencyIdUtil
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.protocol.engine.DID
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

  var agencyDID: DID = _
  implicit var wap: WalletAPIParam = _

  def getAgencyDIDFut: Future[DID] = {
    Option(wap).map { _ =>
      Future.successful(agencyDID)
    }.getOrElse {
      getAgencyDID(agentActorContext.generalCache).flatMap { agencyId =>
        agencyDID = agencyId
        agentActorContext.agentMsgRouter.execute(GetRoute(agencyDID)) map {
          case Some(aa: ActorAddressDetail) =>
            wap = WalletAPIParam(aa.address)
            agencyDID
          case None =>
            agencyDID
        }
      }
    }
  }
}
