package com.evernym.integrationtests.e2e.health

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.http.route_handlers.restricted.ReadinessStatus
import com.evernym.verity.integration.base.verity_provider.VerityAdmin.actorSystem
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import scala.concurrent.Await
import scala.reflect.ClassTag


//Copied from SdkFlowSpec
class HealthCheckSpec
  extends BasicSpec
    with TempDir
    with IntegrationEnv
    with InteractiveSdkFlow
    with SetupFlow
    with AdminFlow
    with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = sys.env.getOrElse("ENVIRONMENT_NAME", StrUtil.classToKebab[HealthCheckSpec])

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env

  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance

  runScenario("sdkFlow")(Scenario(
    "Health check API scenario",
    List(verity1),
    suiteTempDir,
    projectDir,
    defaultTimeout = testEnv.timeout)) { implicit scenario =>

    val apps = ScenarioAppEnvironment(scenario, appEnv, ecp)

    val verityUrl = apps(verity1).urlParam

    s"Health Check Verity Test" - {
      lazy val ledgerUtil: LedgerUtil = buildLedgerUtil(
        appEnv.config,
        ecp.futureExecutionContext,
        ecp.walletFutureExecutionContext,
        Option(appEnv.ledgerConfig.submitterDID),
        Option(appEnv.ledgerConfig.submitterSeed),
        appEnv.ledgerConfig.submitterRole,
        genesisTxnPath = Some(appEnv.ledgerConfig.genesisFilePath)
      )

      "application setup" - {
        sdkAppSetupInteraction(apps, ledgerUtil)
      }

      "test health check" - {
        "liveness should be ok" in {
          val req = Http().singleRequest(HttpRequest(uri = s"$verityUrl/verity/node/liveness"))
          val resp = Await.result(req, 30.seconds)
          resp.status shouldBe OK
          responseAsString(resp) shouldBe "OK"
        }

        "readiness should be ok" in {
          val req = Http().singleRequest(HttpRequest(uri = s"$verityUrl/verity/node/readiness"))
          val resp = Await.result(req, 30.seconds)
          resp.status shouldBe OK
          responseTo[ReadinessStatus](resp) shouldBe ReadinessStatus("OK", "OK", "OK")
        }

        "sdk cleanup" - {
          apps.forEachApplication(cleanupSdk)
        }
      }
    }
  }

  def sdkAppSetupInteraction(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    apps.forEachApplication(availableSdk)
    apps.forEachApplication(setupApplication(_, ledgerUtil))
    apps.forEachApplication(fetchAgencyDetail)
    apps.forEachApplication(provisionAgent)
  }

  //These two methods based on akka.http.scaladsl.testkit.RouteTest
  def responseAsString(response: HttpResponse): String = {
    Await.result(Unmarshal(response.entity).to[String].fast.recover[String] { case _ => fail() }(ecp.futureExecutionContext), 10.seconds)
  }

  def responseTo[T: ClassTag](response: HttpResponse): T = DefaultMsgCodec.fromJson(responseAsString(response))

}
