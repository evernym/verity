package com.evernym.integrationtests.e2e.scenario

import akka.actor.ActorSystem
import com.evernym.integrationtests.e2e.TestConstants
import com.evernym.integrationtests.e2e.client.AdminClient
import com.evernym.integrationtests.e2e.env.AppInstance.AppInstance
import com.evernym.integrationtests.e2e.env.{IntegrationTestEnv, SdkConfig, VerityInstance}
import com.evernym.integrationtests.e2e.scenario.InteractionMode.{Automated, InteractionMode, Manual, Simulated}
import com.evernym.integrationtests.e2e.sdk.VeritySdkProvider
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.testkit.agentmsg.AgentMsgSenderHttpWrapper
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
import com.evernym.verity.util2.{ExecutionContextProvider, UrlParam}
import com.typesafe.config.ConfigFactory

import java.nio.file.Path
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class ApplicationAdminExt(val scenario: Scenario,
                          val instance: VerityInstance,
                          ecp: ExecutionContextProvider,
                          sdkConfigs: List[SdkConfig] = List.empty)
  extends AgentMsgSenderHttpWrapper
    with AdminClient {

  override implicit lazy val system: ActorSystem = scenario.actorSystem

  override def urlParam: UrlParam = instance.endpoint

  override val mockClientAgent =
    new MockEdgeAgent(
      urlParam,
      appConfig,
      ecp.futureExecutionContext,
      system = system
    )

  val sdks: List[VeritySdkProvider] = sdkConfigs.map(VeritySdkProvider.fromSdkConfig(_, scenario))

  val sdk: Option[VeritySdkProvider] = sdks.headOption

  def `sdk_!`: VeritySdkProvider = sdk.get

  def name: String = instance.name

  lazy val conf : ConfigReadHelper = ConfigReadHelper(ConfigFactory.load(s"${instance.appType.toString}/application.conf"))


  lazy val metricsHost : String = {
    val host = conf.getStringReq("kamon.prometheus.embedded-server.hostname") match {
      case "0.0.0.0" => "localhost"
      case x => x
    }
    val port = conf.getIntReq("kamon.prometheus.embedded-server.port")
    s"$host:$port"
  }

  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}

case class ScenarioAppEnvironment(
                                   scenario: Scenario,
                                   testEnv: IntegrationTestEnv,
                                   executionContextProvider: ExecutionContextProvider
                                 ) {
  if (testEnv.isAnyRemoteInstanceExists && testEnv.sdks.isEmpty) {
    throw new RuntimeException("edge agents should be defined with proper endpoint information")
  }

  println("integration test environment config")
  println(testEnv)

  val applications: Map[String, ApplicationAdminExt] = {
    scenario.applications.map { app =>
      val vi = testEnv.instance_!(app.instanceName)
      val sdkConfigs = testEnv.sdks.filter(_.verityInstance == vi).toList
      vi.name -> new ApplicationAdminExt(scenario, vi, executionContextProvider, sdkConfigs)
    }.toMap
  }

  def apply(appInstance: AppInstance): ApplicationAdminExt = applications(appInstance.instanceName)

  def forEachApplication(f: ApplicationAdminExt => Unit): Unit = {
    applications.values.foreach(f)
  }
}

/**
  *
  * @param name scenario name
  * @param applications List of AppInstances to execute the Scenario
  * @param testDir Full path to the temporary directory created to hold output from the Scenario
  * @param projectDir Full path to the project directory
  * @param connIds connection ids (unique strings) for which it will test different agent messages
  * @param restartVerityRandomly determines if verity instance will be restarted randomly before a test
  * @param defaultTimeout Timeout to impose waiting in for messages (expectMsg)
  * @param restartMsgWait how much time it should wait so that message is delivered to recipient
  *                                      before it starts another test which may require agency restart
  */
case class Scenario(name: String,
                    applications: List[AppInstance],
                    testDir: Path,
                    projectDir: Path,
                    connIds: Set[String]=Set.empty,
                    restartVerityRandomly: Boolean = false,
                    defaultTimeout: Option[Duration] = None,
                    restartMsgWait: Option[Long] = Option(TestConstants.defaultWaitTime)) {

  val actorSystem: ActorSystem = ActorSystemVanilla("TestingActorSystem")

  val timeout:Duration = {
    defaultTimeout
      .getOrElse(TestConstants.defaultTimeout)
  }

  val consumerMode: InteractionMode = {
    sys.env.get("CONSUMER_MANUAL")
      .map(_ => Manual)
      .orElse{
        sys.env.get("CONSUMER_AUTOMATION")
          .map(_ => ???)// Not supported
      }
      .getOrElse(Simulated)
  }
}

object Scenario {
  val scenarioEnvKey = "TEST_SCENARIOS"
  val restartVerityEnvKey = "TEST_RESTART_VERITY_RANDOMLY"

  def restartVerityRandomly(noEnvVar: Boolean=false, map:Map[String, String] = sys.env): Boolean = {
    map.get(restartVerityEnvKey) match {
      case None => noEnvVar
      case Some(x) => x == "true"
    }
  }

  def isRunScenario(name: String, noEnvVar: Boolean=true, map:Map[String, String] = sys.env): Boolean = {
    map.get(scenarioEnvKey) match {
      case None => noEnvVar
      case Some(x) => x.split(",").map(_.trim).contains(name)
    }
  }

  def runScenario(name: String, noEnvVar: Boolean=true, map:Map[String, String] = sys.env)
                 (scenario: =>Scenario)
                 (test: Scenario=>Unit): Unit = {
    if(isRunScenario(name, noEnvVar, map)) {
      val s = scenario
      test(s)

    }
  }
}

object Interactive {
  /**
    * buffer time to allow an action to complete
    *
    * @param time
    * @param scenario
    */
  def buffer(time: Duration = Duration("1.5 sec"))(implicit scenario: Scenario): Unit = {
    scenario.consumerMode match {
      case Manual|Automated => Thread.sleep(time.toMillis)
      case _ =>
    }
  }
}

object InteractionMode {
  sealed trait InteractionMode

  case object Simulated extends InteractionMode
  case object Manual extends InteractionMode
  case object Automated extends InteractionMode

  def consumerAction(action:  InteractionMode ?=> Unit)(implicit scenario: Scenario): Unit = {
    if(action.isDefinedAt(scenario.consumerMode)) {
      action(scenario.consumerMode)
    }
    else {
      throw new Exception(s"InteractionMode ${scenario.consumerMode} is not defined for this action.")
    }
  }
}
