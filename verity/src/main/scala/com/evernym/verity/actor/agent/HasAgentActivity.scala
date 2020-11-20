package com.evernym.verity.actor.agent

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.actor.{ForIdentifier, ShardRegionCommon}
import com.evernym.verity.actor.metrics.{ActivityTracking, ActivityWindow, AgentActivity}
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.metrics.CustomMetrics.AS_NEW_USER_AGENT_COUNT
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.util.OptionUtil.optionToEmptyStr
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait HasAgentActivity extends ShardRegionCommon {
  object AgentActivityTracker {
    private val logger: Logger = getLoggerByName("AgentActivityTracker")

    private def sendToRegion(id: String, msg: ActivityTracking): Unit =
      activityTrackerRegion ! ForIdentifier(id, msg)

    //FIXME -> RTM: Ask devin about agentActorContext - is this breaching encapsulation?
    def track(agentActorContext: AgentActorContext,
              msgType: String,
              domainId: String,
              relId: Option[String],
              sponsorRel: Option[SponsorRel]=None): Unit = {
      getSponsorRel(agentActorContext, domainId, sponsorRel).onComplete {
        case Success(s) =>
          sendToRegion(
            domainId,
            AgentActivity(domainId, TimeUtil.nowDateString, s, msgType, relId)
          )
        case Failure(s) => logger.warn(s"failure getting sponsor details: $s")
      }
    }

    private def getSponsorRel(agentActorContext: AgentActorContext,
                              domainId: DomainId,
                              sponsorRel: Option[SponsorRel]): Future[Option[SponsorRel]] = {
      if (sponsorRel.isEmpty)
        agentActorContext
          .agentMsgRouter
          .execute(InternalMsgRouteParam(domainId, GetSponsorRel))
          .asInstanceOf[Future[Option[SponsorRel]]]
          .recover {
            case e => logger.warn(s"expected type SponsorRel, got: $e"); None
          }
      else Future(sponsorRel)
    }


    def newAgent(sponsorId: Option[String]=None): Unit =
      MetricsWriter.gaugeApi.incrementWithTags(AS_NEW_USER_AGENT_COUNT, Map("sponsorId" -> sponsorId))

    def setWindows(domainId: String, windows: ActivityWindow): Unit =
      sendToRegion(domainId, windows)
  }

}
