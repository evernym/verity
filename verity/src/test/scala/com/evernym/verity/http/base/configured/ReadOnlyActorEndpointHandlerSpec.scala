package com.evernym.verity.http.base.configured

import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.maintenance.SummaryData
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpecLike
import com.evernym.verity.actor.persistence.recovery.latest.verity2.vas.{AgencyAgentEventSetter, UserAgentEventSetter}
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.constants.ActorNameConstants.USER_AGENT_REGION_ACTOR_NAME
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext

class ReadOnlyActorEndpointHandlerSpec
  extends BasicSpecWithIndyCleanup
    with EdgeEndpointBaseSpec
    with ProvidesMockPlatform
    with BaseRecoverySpecLike
    with AgencyAgentEventSetter
    with UserAgentEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    closeClientWallets(Set(myAgencyAgentEntityId, mySelfRelAgentEntityId))
  }

  val basePath = s"/agency/internal/maintenance/persistent-actor/$USER_AGENT_REGION_ACTOR_NAME/$mySelfRelAgentEntityId/data"

  "ReadOnlyEndpointHandler" - {

    "when requested summary" - {
      "should respond with summary" in {
        buildGetReq(s"$basePath/summary") ~> epRoutes ~> check {
          status shouldBe OK
          val sd = responseTo[SummaryData]
          sd.recoveredSnapshot shouldBe false
          sd.recoveredEvents >= 19
        }
      }
    }

    "when requested aggregated messages in plain format" - {
      "should respond with aggregated messages" in {
        buildGetReq(s"$basePath/aggregated") ~> epRoutes ~> check {
          status shouldBe OK
          val ad = responseTo[String]
          ad.split("\n").length shouldBe 10
        }
      }
    }

    "when requested aggregated messages in html format" - {
      "should respond with aggregated messages" in {
        buildGetReq(s"$basePath/aggregated?asHtml=Y") ~> epRoutes ~> check {
          status shouldBe OK
          val ad = responseAs[String]
          ad.split("<br><br>").length shouldBe 10
        }
      }
    }

    "when requested all messages" - {
      "should respond with all messages without actual data" in {
        buildGetReq(s"$basePath/all") ~> epRoutes ~> check {
          status shouldBe OK
          val ad = responseTo[String]
          ad.split("\n").length shouldBe 19
        }
      }

      "should respond with all messages with data" in {
        buildGetReq(s"$basePath/all?withData=Y") ~> epRoutes ~> check {
          status shouldBe OK
          val ad = responseTo[String]
          ad.split("\n").length shouldBe 19
        }
      }
    }
  }

  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory.parseString (
      """
        verity.internal-api.persistent-data.enabled = true""".stripMargin
    )
  }

  override lazy val agentActorContext: AgentActorContext = platform.agentActorContext

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
