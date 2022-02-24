package com.evernym.integrationtests.e2e.env

import java.nio.file.{Path, Paths}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.fixture.TempDir
import com.evernym.integrationtests.e2e.env.AppType.AppType
import com.evernym.integrationtests.e2e.env.PrintLineUtil._
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.{BeforeAndAfterAll, TestSuite}

import scala.language.postfixOps
import scala.sys.process._

object EnvUtils {

  private val ENV_STATUS_UNINITIALIZED = "uninitialized"
  private val ENV_STATUS_PREPARING = "preparing"      //jars are being prepared
  private val ENV_STATUS_PREPARED = "prepared"        //jars created
  private val ENV_STATUS_STARTING = "starting"        //instances starting in progress
  private val ENV_STATUS_STARTED = "started"          //instances started
  private val ENV_STATUS_STOPPING = "stopping"        //instances stopping in progress
  private val ENV_STATUS_STOPPED = "stopped"          //instances stopped

  trait IntegrationEnv extends IntegrationTestEnvBuilder with BeforeAndAfterAll {
    this: TestSuite with TempDir =>
    val projectDir: Path = Paths.get(".").toAbsolutePath.normalize()
    val env: Environment = new Environment(projectDir, suiteTempDir)

    lazy val appConfig: AppConfig = testEnv.config

    //these names must be same what is defined in environment.conf under "verity-instance-configs" config
    // (in integration-tests/src/test/resources)
    // (in integration-tests/src/test/resources)
    val APP_NAME_VERITY_1 = "VERITY1"
    val APP_NAME_VERITY_2 = "VERITY2"
    val APP_NAME_CAS_1    = "CAS1"
    val APP_NAME_EAS_1    = "EAS1"
    val APP_NAME_CAS_2    = "CAS2"
    val APP_NAME_EAS_2    = "EAS2"

    override def beforeAll(): Unit = {
      super.beforeAll()
      if (System.getenv("TAA_ACCEPT_DATE") != java.time.LocalDate.now.toString){
        throw new RuntimeException("Looks like TAA_ACCEPT_DATE env var is not defined. Check README.md")
      }
      else {
        logger.info("TAA accept date is ok")
      }
      if (env.getEnvStatus == ENV_STATUS_UNINITIALIZED) {
        env.setupEnv(testEnv)
        env.startEnv(testEnv)
      }
    }

    override def afterAll(): Unit = {
      Thread.sleep(5000) // this sleep time is necessary for allowing pending requests to be completed
      env.stopEnv(testEnv)
      super.afterAll()
    }
  }

  class Environment(val projectDir: Path, val testSuiteDir: Path) {
    import Environment._

    private var status = ENV_STATUS_UNINITIALIZED

    def getEnvStatus: String = status

    private def runInstanceSetupScript(scriptPath: Path, instanceNames: Set[String]): String = {
      val appNames = instanceNames.mkString(",")
      s"""bash $scriptPath $projectDir $testSuiteDir $appNames""" !!
    }

    private def runInstanceStartScript(scriptPath: Path, appType: AppType, appName: String,
                                       restart: Boolean, envVars: List[EnvVar]=List.empty): String = {
      val cmd = s"""bash $scriptPath $projectDir $testSuiteDir $appType $appName $restart"""
      val pc = Process(cmd, None, buildEnvVarsTuple(envVars):_*)
      pc.!!

    }

    private def runInstanceStopScript(scriptPath: Path, appName: String, envVars: List[EnvVar]=List.empty): String = {
      val cmd = s"""bash $scriptPath $projectDir $testSuiteDir $appName"""
      val pc = Process(cmd, None, buildEnvVarsTuple(envVars):_*)
      pc.!!
    }

    def buildEnvVarsTuple(envVars: List[EnvVar]=List.empty): List[(String, String)] = {
      envVars.map(r => (r.name, r.value.toString))
    }

    def setupEnv(testEnv: IntegrationTestEnv): Unit = {
      if (testEnv.verityInstances.exists(_.setup)) {
        status = ENV_STATUS_PREPARING
        println(s"\n\nSetting up environment")
        println(s"  project dir    : $projectDir")
        printlnWithResetConsole(s"  test suite dir : " + Console.BOLD  + s"$testSuiteDir")
        runTearDown(testEnv)
        println(s"  executing setup.sh ...")
        println(s"     * if genesis file points to a local ledger and if that file is changed")
        println(s"       then the ledger data should also be deleted before this test starts")
        println(s"     * if genesis file points to remote ledger and if there is at least one local verity instance")
        println(s"       then those local verity instances should be able to communicate with that remote ledger")
        println("")
        printlnWithResetConsole(Console.BOLD + s"     * !!! current step may take some time if it has to recreate jars !!!\n")
        val res = runInstanceSetupScript(setupScript(projectDir), testEnv.verityInstances.map(_.name))

        println(s"  executed setup.sh" + res)
        Thread.sleep(5000)
        checkResultAndUpdateStatus(res, "setup completed", ENV_STATUS_PREPARED)
      }
    }

    def startEnv(testEnv: IntegrationTestEnv, restart: Boolean=false): Unit = {
      status = ENV_STATUS_STARTING
      testEnv.allLocalInstances.foreach { vi =>
        val envVars = buildEnvVars(testEnv, vi)
        if (!restart) {
          println(s"${vi.name} environment variables: \n-------------------------\n" +
            envVars.mkString("\n"))
        }
        startVerityProcess(vi.appType, vi.name, restart, envVars)
      }
      updateStatus(ENV_STATUS_STARTED)

      def startVerityProcess(appType: AppType, appName: String, restart: Boolean, envVars: List[EnvVar]): String = {
        println(s"\n\nstarting verity process (name: $appName) [isRestart=$restart]...")
        val res = runInstanceStartScript(startScript(projectDir), appType, appName, restart, envVars)
        checkScriptExecutionResult(res, "verity process started")
        println(s"starting verity process done\n\n")
        res
      }
    }

    def stopEnv(testEnv: IntegrationTestEnv): Unit = {
      status = ENV_STATUS_STOPPING
      runTearDown(testEnv)
      updateStatus(ENV_STATUS_STOPPED)
      Thread.sleep(5000)
    }

    private def runTearDown(testEnv: IntegrationTestEnv): Unit = {
      println(s"  executing teardown.sh ...")
      testEnv.allLocalInstances.foreach { vi =>
        val envVars = buildEnvVars(testEnv, vi)
        val res = runInstanceStopScript(teardownScript(projectDir), vi.name, envVars)
        checkScriptExecutionResult(res, "tear-down-completed")
      }
      println(s"  executed teardown.sh")
    }

    private def checkResultAndUpdateStatus(res: String, resStatus: String, newStatus: String): Unit = {
      checkScriptExecutionResult(res, resStatus)
      updateStatus(newStatus)
    }

    private def updateStatus(newStatus: String): Unit = {
      status = newStatus
    }

    private def checkScriptExecutionResult(actualResponse: String, expectedStatus: String): Unit = {
      val actualStatus = actualResponse.split("\n").lastOption
      if (! actualStatus.contains(expectedStatus)) {
        throw new RuntimeException("script executed with error: " + actualResponse)
      }
    }

    def buildEnvVars(te: IntegrationTestEnv, instance: VerityInstance): List[EnvVar] = {
      te.commonEnvVars ++ instance.specificEnvVars
    }
  }

  object Environment {
    def setupScript(projectDir: Path): Path = {
      projectDir.resolve("integration-tests/src/test/setup.sh")
    }
    def startScript(projectDir: Path): Path = {
      projectDir.resolve("integration-tests/src/test/start.sh")
    }
    def teardownScript(projectDir: Path): Path = {
      projectDir.resolve("integration-tests/src/test/teardown.sh")
    }
  }
}


object PrintLineUtil {

  def printlnWithResetConsole(msg: String): Unit = {
    println(msg + Console.RESET)
  }
}
