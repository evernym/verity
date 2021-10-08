package com.evernym.integrationtests.e2e.health

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import com.evernym.integrationtests.e2e.env.AppInstance.Verity
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.http.route_handlers.restricted.ReadinessStatus
import com.evernym.verity.integration.base.verity_provider.VerityAdmin.actorSystem
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.util.StrUtil
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import scala.concurrent.Await
import scala.reflect.ClassTag


//Copied from SdkFlowSpec, some code has been deleted
class HealthCheckSpec
  extends BasicSpec
    with TempDir
    with IntegrationEnv
    with InteractiveSdkFlow
    with SetupFlow
    with AdminFlow
    with MetricsFlow
    with CancelGloballyAfterFailure
    with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = sys.env.getOrElse("ENVIRONMENT_NAME", StrUtil.classToKebab[HealthCheckSpec])

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env

  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance
  val limitsCredDefName = "creds_for_limits"

  //TODO: need find example to get `listening port` param from config
  val port = 9003

  runScenario("sdkFlow")(Scenario(
    "SDK Workflow to test protocols",
    List(verity1),
    suiteTempDir,
    projectDir,
    defaultTimeout = testEnv.timeout)) { implicit scenario =>

    val apps = ScenarioAppEnvironment(scenario, appEnv, ecp)

    val sdkUnderTest = apps(verity1)
      .sdk
      .getOrElse(throw new Exception("Verity SDK must be defined for this Suite"))
      .sdkType

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

      //Possibly Await is bad
      "test health check" - {
        "liveness should be ok" in {
          val req = Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/verity/node/liveness"))
          val resp = Await.result(req, 30.seconds)
          resp.status shouldBe OK
          responseAsString(resp) shouldBe "OK"
        }

        "readiness should be ok" in {
          val req = Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/verity/node/readiness"))
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

  //These two methods based on akka.http.scaladsl.testkit.RouteTest and maybe not good
  def responseAsString(response: HttpResponse): String = {
    Await.result(Unmarshal(response.entity).to[String].fast.recover[String] { case _ => fail() }(ecp.futureExecutionContext), 10.seconds)
  }

  def responseTo[T: ClassTag](response: HttpResponse): T = DefaultMsgCodec.fromJson(responseAsString(response))


}

object HealthCheckSpec {
  def specifySdkForType(sdkType: String, version: String, env: IntegrationTestEnv): IntegrationTestEnv = {
    val sdks = env.sdks
    val specified = sdks
      .map { s =>
        s.verityInstance.appType match {
          case Verity => s.copy(sdkTypeStr = sdkType, version = Some(version))
          case _ => s
        }
      }

    env.copy(sdks = specified)
  }

  def metricKey(msgFamily: MsgFamily): String = s"${msgFamily.name}[${msgFamily.version}]"
}
