package com.evernym.integrationtests.e2e.env

import com.evernym.integrationtests.e2e.env.AppInstance.{AppInstance, Consumer, Enterprise, Verity}
import com.evernym.integrationtests.e2e.env.AppType.AppType
import com.evernym.integrationtests.e2e.env.SdkType.SdkType
import com.evernym.integrationtests.e2e.util.PortProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.did.DidStr
import com.evernym.verity.util.CollectionUtil.containsDuplicates
import com.evernym.verity.util2.UrlParam
import com.typesafe.config.{Config, ConfigFactory, ConfigObject, ConfigValueFactory}
import com.typesafe.scalalogging.Logger
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils

import java.io.File
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.Random

object Constants {
  final val ENVIRONMENT_CONF_FILE_NAME = "environment.conf"
  final val DEFAULT_ENV_NAME = "default"
  final val INTEGRATION_TEST = "integration-test"
  final val ENVIRONMENTS = "environments"
  final val NAME = "name"
  final val ENDPOINT = "endpoint"
  final val LISTENING_PORT = "listening-port"
  final val JDWP_PORT= "jdwp-port"
  final val MESSAGE_TRACKING_ENABLED = "message-tracking-enabled"
  final val MESSAGE_TRACKING_BY_ID = "message-tracking-by-id"

  final val ENV_PARTICIPANTS = "participants"
  final val ENV_TIMEOUT = "timeout"
  final val VERITY_INSTANCE_CONFIGS = "verity-instance-configs"
  final val VERITY_INSTANCE_NAME = NAME
  final val VERITY_INSTANCE_APP_TYPE = "app-type"
  final val VERITY_INSTANCE_SETUP = "setup"
  final val VERITY_INSTANCE_ENDPOINT = ENDPOINT
  final val VERITY_INSTANCE_LISTENING_PORT = LISTENING_PORT
  final val VERITY_INSTANCE_JDWP_PORT = JDWP_PORT
  final val VERITY_INSTANCE_MESSAGE_TRACKING_ENABLED = MESSAGE_TRACKING_ENABLED
  final val VERITY_INSTANCE_MESSAGE_TRACKING_BY_ID = MESSAGE_TRACKING_BY_ID
  final val VERITY_INSTANCE_SEED = "seed"
  final val VERITY_INSTANCE_SPONSOR_SEED = "sponsor-seed"
  final val VERITY_INSTANCE_REQUIRE_SPONSOR = "require-sponsor"

  final val SDKS = "sdks"
  final val SDK_CONFIGS = "sdk-configs"
  final val SDK_TYPE= "type"
  final val SDK_NAME = NAME
  final val SDK_VERSION = "version"
  final val SDK_PORT = "port"
  final val SDK_ENDPOINT = ENDPOINT
  final val SDK_DOMAIN_DID = "domain-did"
  final val SDK_AGENT_VERKEY = "verkey"
  final val SDK_RECEIVE_SPACING = "receive-spacing"
  final val SDK_SEED = "seed"
  final val SDK_VERITY_INSTANCE = "verity-instance"

  final val LEDGER_CONFIG = "ledger-config"
  final val LEDGER_CONFIGS = "ledger-configs"
  final val LEDGER_GENESIS_FILE_PATH = "genesis-file-path"
  final val LEDGER_GENESIS_IP_ADDR = "genesis-ip-addr"
  final val LEDGER_GENESIS_SUBMITTER_DID = "submitter-did"
  final val LEDGER_GENESIS_SUBMITTER_SEED = "submitter-seed"
  final val LEDGER_GENESIS_SUBMITTER_ROLE = "submitter-role"
  final val LEDGER_TIMEOUT = "timeout"
  final val LEDGER_EXTENDED_TIMEOUT = "extended-timeout"
  final val LEDGER_PROTOCOL_VERSION = "protocol-version"

  final val SEED_CONFLICT_CHECK = "seed-conflict-check"
}

import com.evernym.integrationtests.e2e.env.Constants._

trait IntegrationTestEnvBuilder {
  import IntegrationTestEnvBuilder._

  val logger: Logger //= getLoggerByClass(classOf[IntegrationTestEnvBuilder])

  val suiteTempDir: Path

  //this points to environment name config to be used from "environment.conf"
  //actual spec may override it if it want to use specific configuration
  def environmentName: String = DEFAULT_ENV_NAME

  lazy val integrationTestConfig: ConfigReadHelper =
    new ConfigReadHelper(ConfigFactory.load(ENVIRONMENT_CONF_FILE_NAME).getConfig(INTEGRATION_TEST))

  lazy val environmentNameConfigKey = s"$ENVIRONMENTS.$environmentName"

  lazy val testEnvConfig: Config = integrationTestConfig.config.getConfig(environmentNameConfigKey)

  lazy val testEnvConfigReadHelper = new ConfigReadHelper(testEnvConfig)

  lazy val testEnv: IntegrationTestEnv = try {
    val ledgerConfig = prepareLedgerConfig
    val (verityInstanceMap, sdkMap) = prepareEnv(ledgerConfig)
    val seedConflictCheck = integrationTestConfig.getBooleanReq(SEED_CONFLICT_CHECK)
    val timeout = testEnvConfigReadHelper.getStringOption(ENV_TIMEOUT).map(Duration(_))
    IntegrationTestEnv(sdkMap.values.toSet, verityInstanceMap.values.toSet, ledgerConfig, seedConflictCheck, timeout)
  } catch {
    case e: RuntimeException =>
      logger.error("Error occurred during preparing integration test environment: " + e.getMessage)
      throw e
  }



  def prepareEnv(ledgerConfig: LedgerConfig): (Map[String, VerityInstance], Map[String, SdkConfig]) = {
    val (instanceList, sdkList) = testEnvConfigReadHelper
    .config
    .getObjectList(ENV_PARTICIPANTS)
    .asScala
    .map(prepareVerity(_, ledgerConfig))
    .unzip

    if (containsDuplicates(instanceList.map(_.name))) {
      throw new RuntimeException(s"'$VERITY_INSTANCE_CONFIGS' should have unique names")
    }
    if (containsDuplicates(sdkList.flatten.map(_.name))) {
      throw new RuntimeException(s"'$SDK_CONFIGS' should have unique names")
    }

    val instances = instanceList
      .map(i => i.name -> i)
      .toMap

    val sdks = sdkList
      .flatten
      .map(i => i.name -> i)
      .toMap

    instances -> sdks
  }

  def prepareVerity(participant: ConfigObject, ledgerConfig: LedgerConfig): (VerityInstance, Seq[SdkConfig]) = {
    val config = participant.toConfig
    val key = config.getString("instance")

    val instanceConfig = new ConfigReadHelper(
      integrationTestConfig.config.getConfig(s"$VERITY_INSTANCE_CONFIGS.$key")
    )
    val name = stringReq(instanceConfig, VERITY_INSTANCE_NAME, key)
    val appType = AppType.fromString(stringReq(instanceConfig, VERITY_INSTANCE_APP_TYPE, key))
    val setup = booleanReq(instanceConfig, VERITY_INSTANCE_SETUP, key)
    val endpoint = stringOption(instanceConfig, VERITY_INSTANCE_ENDPOINT, key)
    val seed = if (setup) stringOption(instanceConfig, VERITY_INSTANCE_SEED, key) else None
    val sponsorSeed = if (setup) stringOption(instanceConfig, VERITY_INSTANCE_SPONSOR_SEED, key) else None


    val listeningPort = if (setup) {
      Option(intReq(instanceConfig, VERITY_INSTANCE_LISTENING_PORT, key))
    } else {
      intOption(instanceConfig, VERITY_INSTANCE_LISTENING_PORT, key)
    }

    val jdwpPort: Option[Int] = intOption(instanceConfig, VERITY_INSTANCE_JDWP_PORT, key)
    val trackMessages: Option[Boolean] = booleanOption(instanceConfig, VERITY_INSTANCE_MESSAGE_TRACKING_ENABLED, key)
    val trackMessagesById: Option[String] = stringOption(instanceConfig, VERITY_INSTANCE_MESSAGE_TRACKING_BY_ID, key)

    val requireSponsor = booleanOption(instanceConfig, VERITY_INSTANCE_REQUIRE_SPONSOR, key)

    val instance = VerityInstance(
      name,
      appType,
      setup,
      endpoint,
      listeningPort,
      seed,
      sponsorSeed,
      ledgerConfig,
      jdwpPort,
      trackMessages,
      trackMessagesById,
      requireSponsor
    )

    val sdks = ConfigReadHelper(config)
      .getStringListOption(SDKS)
      .getOrElse(Seq.empty)
      .map { s =>
        prepareSdk(s, instance)
      }

    instance -> sdks
  }


  def prepareSdk(key: String, instance: VerityInstance): SdkConfig = {
    val config = new ConfigReadHelper(
      integrationTestConfig.config.getConfig(s"$SDK_CONFIGS.$key")
    )

    val sdkType = config.getStringReq(SDK_TYPE)
    val name = stringReq(config, SDK_NAME, key)
    val version = stringOption(config, SDK_VERSION, key)
    val port = intOption(config,SDK_PORT, key)
    val endpoint = stringOption(config, SDK_ENDPOINT, key)
    val domainDID = stringOption(config, SDK_DOMAIN_DID, key)
    val agentVerkey = stringOption(config, SDK_AGENT_VERKEY, key)
    val spacing = stringOption(config, SDK_RECEIVE_SPACING, key).map(Duration.apply)
    val keySeed = stringOption(config, SDK_SEED, key)

    SdkConfig(sdkType, name, version, port, endpoint, domainDID, agentVerkey, keySeed, spacing, instance)
  }

  def prepareLedgerConfig: LedgerConfig = {
    val confName = testEnvConfigReadHelper.getStringOption(LEDGER_CONFIG).getOrElse("default")
    val ledgerConfig = integrationTestConfig.config.getConfig(s"$LEDGER_CONFIGS.$confName")
    val conf = new ConfigReadHelper(ledgerConfig)


    val genesisFilePath = resolveGenesisFile(conf, confName, suiteTempDir)

    val trusteeDID = stringReq(conf, LEDGER_GENESIS_SUBMITTER_DID, confName)
    val trusteeSeed = stringReq(conf, LEDGER_GENESIS_SUBMITTER_SEED, confName)
    val trusteeRole = stringReq(conf, LEDGER_GENESIS_SUBMITTER_ROLE, confName)
    val timeout = conf.getIntReq(LEDGER_TIMEOUT)
    val extendedTimeout = conf.getIntReq(LEDGER_EXTENDED_TIMEOUT)
    val protocolVersion = conf.getIntReq(LEDGER_PROTOCOL_VERSION)
    LedgerConfig(genesisFilePath, trusteeDID, trusteeSeed, trusteeRole, timeout, extendedTimeout, protocolVersion)
  }
}

object IntegrationTestEnvBuilder {
  private def get [T](findConfig: String => T,
                      findEnv: String => T,
                      key: String,
                      instanceName: String): T = {
    sys.env.get(s"$instanceName-$key") match  {
      case Some(s) => findEnv(s)
      case None    => findConfig(key)
    }
  }

  // These functions allow a simple environment variable to override config single value in environment.conf

  def stringReq(config: ConfigReadHelper, key: String, instanceKey: String): String = {
    get(config.getStringReq, identity, key, instanceKey)
  }

  def stringOption(config: ConfigReadHelper, key: String, instanceKey: String): Option[String] = {
    get(config.getStringOption, Option.apply, key, instanceKey)
  }

  def booleanReq(config: ConfigReadHelper, key: String, instanceKey: String): Boolean = {
    get(config.getBooleanReq, _.toBoolean, key, instanceKey)
  }

  def booleanOption(config: ConfigReadHelper, key: String, instanceKey: String): Option[Boolean] = {
    get(config.getBooleanOption, { x => Some(x.toBoolean)}, key, instanceKey)
  }

  def intReq(config: ConfigReadHelper, key: String, instanceKey: String): Int = {
    get(config.getIntReq, _.toInt, key, instanceKey)
  }

  def intOption(config: ConfigReadHelper, key: String, instanceKey: String): Option[Int] = {
    get(config.getIntOption, { x => Some(x.toInt)}, key, instanceKey)
  }

  def resolveGenesisFile(conf: ConfigReadHelper, confName: String, tempDir: Path): String = {
    stringOption(conf, LEDGER_GENESIS_FILE_PATH, confName)
    .map{ p =>
      val rtn = new File(p).getAbsoluteFile
      assert(rtn.getAbsoluteFile.exists())
      rtn
    }
    .orElse{
      stringOption(conf, LEDGER_GENESIS_IP_ADDR, confName)
        .map(addr => s"http://$addr:5679/genesis.txt")
        .map { u =>
          val e = HttpClientBuilder
            .create
            .build
            .execute(new HttpGet(u))
            .getEntity

          val genesisContent = EntityUtils.toString(e)

          val tempFile = tempDir.resolve("genesis.txn")

          Files.write(tempFile, genesisContent.getBytes)
          tempFile.toFile
        }
    }
    .map(_.getPath)
    .getOrElse(throw new Exception("Must have Genesis Configuration"))
  }
}

object VerityInstance {

  def apply(name: String,
            appType: AppType,
            setup: Boolean,
            endpointOpt: Option[String],
            listeningPort: Option[Int],
            seed: Option[String],
            sponsorSeed: Option[String],
            ledgerConfig: LedgerConfig,
            jdwpPort: Option[Int],
            messageTrackingEnabled: Option[Boolean],
            messageTrackingById: Option[String],
            requireSponsor: Option[Boolean]): VerityInstance = {
    VerityInstance(
      name,
      appType,
      setup,
      optionToEndpoint(endpointOpt, setup, listeningPort),
      listeningPort,
      seed,
      sponsorSeed,
      ledgerConfig,
      jdwpPort,
      messageTrackingEnabled,
      messageTrackingById,
      requireSponsor)
  }

  def optionToEndpoint(endpointOpt: Option[String], setup: Boolean, listeningPort: Option[Int]): UrlParam = {
    def throwException(reason: String): Nothing = {
      throw new RuntimeException(s"'$ENDPOINT' or '$LISTENING_PORT' is $reason")
    }

    (setup, endpointOpt.map(UrlParam(_)), listeningPort) match {
      case (_,      Some(ep), None        )                     => ep
      case (true,   None,     Some(port)  )                     => UrlParam(s"localhost:$port")
      case (true,   Some(ep), Some(_)     ) if ! ep.isLocalhost => ep
      case (true,   None,     None        )                     => throwException("required")
      case (true,   Some(ep), Some(port)  ) if ep.port != port  => throwException("pointing to different ports")
      case (false,  None,     None        )                     => throwException("required")
      case (false,  None,     Some(_)     )                     => throwException("not required for remote instance")

      case _                                                    =>
        throw new RuntimeException("invalid configuration or not supported")
    }
  }
}

object AppType {
  sealed trait AppType {
    //values should not be changed as those are hard coded (at least as of now) in each application's Main.scala
    def isType(t: AppType): Boolean = this == t
    def systemName: String

    /**
     * this is used during passing app type name from scala code to 'start.sh' script
     * @return
     */
    override def toString: String = {
      this.getClass.getSimpleName
        .replace("$", "") // Remove $ from object class names
        .toLowerCase()
    }
  }
  def fromString(appType: String): AppType = {
    appType.toLowerCase match {
      case "consumer" => Consumer
      case "enterprise" => Enterprise
      case "verity" => Verity
      case t => throw new RuntimeException(s"Unknown app-type '$t', must be a known type")
    }
  }
}

object AppInstance {


  object Consumer extends AppType {
    override def systemName: String = "consumer-agency"
  }
  object Enterprise extends AppType {
    override def systemName: String = "enterprise-agency"
  }
  object Verity extends AppType {
    override def systemName: String = "verity-application"
  }

  sealed trait AppInstance {
    //values should not be changed as those are hard coded (at least as of now) in each application's Main.scala
    def appType: AppType
    def instanceName: String
    def isType(t: AppType): Boolean = this.appType == t

    override def toString: String = instanceName
  }

  case class Consumer(instanceName: String) extends AppInstance {
    override def appType: AppType = Consumer
  }
  case class Enterprise(instanceName: String) extends AppInstance {
    override def appType: AppType = Enterprise
  }
  case class Verity(instanceName: String) extends AppInstance {
    override def appType: AppType = Verity
  }

  def fromNameAndType(name: String, appType: AppType): AppInstance = {
    appType match {
      case Consumer   => Consumer(name)
      case Enterprise => Enterprise(name)
      case Verity     => Verity(name)
      case t => throw new RuntimeException(s"Unknown app-type '$t', must be a known type")
    }
  }
}

/**
  *
  * @param name instance name (like cas1, eas1 etc)
  * @param appType one of these (like consumer, enterprise, verity)
  * @param setup determines if this instance is supposed to setup/start locally or not
  * @param endpoint instance's endpoint (for example: Endpoint(localhost:9000)
  * @param listeningPort          (optional) only required for local instance
  *                               port where this verity instance will start http listener
  * @param seed                   (optional) only application for locally setup instances
  *                               if given, agency agent's key would be created based on that seed
  * @param ledgerConfig           the ledger configuration
  * @param jdwpPort               (optional) Allows for remove debugging. Defaults to 5005.
  * @param messageTrackingEnabled track message progress (message profiling).
  * @param messageTrackingById    (optional) "global" > "<ip-address>" > "<domain>" > "<relationship>"
  *                               It is ok to start with either “global” or “ip-address” based tracking, but the moment
  *                               you know the “domain” or the “connection” tracking identifier, then, you should start
  *                               tracking those and stop tracking “global” and/or “ip-address” tracking.
  *
  */
case class VerityInstance(name: String,
                          appType: AppType,
                          setup: Boolean,
                          endpoint: UrlParam,
                          listeningPort: Option[Int]=None,
                          seed: Option[String]=None,
                          sponsorSeed: Option[String]=None,
                          ledgerConfig: LedgerConfig,
                          jdwpPort: Option[Int]=None,
                          messageTrackingEnabled: Option[Boolean]=None,
                          messageTrackingById: Option[String]=None,
                          requireSponsor: Option[Boolean] = None
                         ) {

  /**
    * environment variable needed to run this specific instance
    * @return
    */

  lazy val specificEnvVars: List[EnvVar] = {
    val verityAkkaRemotePort = PortProvider.firstFreePortFrom2000
    val verityAkkaManagementPort = PortProvider.firstFreePortFrom2000
    List(
      EnvVar("APP_ACTOR_SYSTEM_NAME", appType.systemName),
      EnvVar("POOL_NAME", s"${name}_pool"),
      EnvVar("JDWP_PORT", jdwpPort.getOrElse("5005"), uniqueValueAcrossEnv = true),
      EnvVar("REQUIRE_SPONSOR", requireSponsor.map(_.toString).getOrElse("false")),
      EnvVar("MESSAGE_TRACKING_ENABLED", messageTrackingEnabled.getOrElse(false)),
      EnvVar("MESSAGE_TRACKING_BY_ID", messageTrackingById.getOrElse("global")),

      EnvVar("VERITY_ENDPOINT_HOST", endpoint.host),
      EnvVar("VERITY_ENDPOINT_PORT", endpoint.port),
      EnvVar("VERITY_HTTP_PORT", listeningPort.get, uniqueValueAcrossEnv = true),
      EnvVar("VERITY_AKKA_REMOTE_PORT", verityAkkaRemotePort, uniqueValueAcrossEnv = true),
      EnvVar("VERITY_AKKA_MANAGEMENT_HTTP_PORT",  verityAkkaManagementPort, uniqueValueAcrossEnv = true),
      EnvVar("GENESIS_TXN_FILE_LOCATION", ledgerConfig.genesisFilePath)
    )
  }

  def isRunningLocally: Boolean = setup

  def appInstance: AppInstance = AppInstance.fromNameAndType(name, appType)

  override def toString: String = {
    s"[$name] type:$appType - setup:$setup - endpoint:$endpoint - seed:$seed - jdwp:$jdwpPort"
  }
}

object SdkType {
  sealed trait SdkType {
    def isType(t: SdkType): Boolean = this == t

    override def toString: String = {
      this.getClass.getSimpleName
        .replace("$", "") // Remove $ from object class names
        .toLowerCase()
    }
  }

  case object Java extends SdkType
  case object Vcx extends SdkType
  case object Python extends SdkType
  case object Node extends SdkType
  case object Manual extends SdkType
  case object Rest extends SdkType
  case object DotNet extends SdkType

  def fromString(str: String): SdkType = {
    str.toLowerCase match {
      case "java"   => Java
      case "vcx"    => Vcx
      case "python" => Python
      case "node"   => Node
      case "manual" => Manual
      case "rest"   => Rest
      case "dotnet" => DotNet
      case t => throw new RuntimeException(s"Unknown sdk-type '$t', must be a known type")
    }
  }
}

/**
  * Sdk configuration
  *
  * @param name            name of the SDK
  * @param port            port where http listener will be started
  * @param endpointStr        accessible endpoint which will be registered with cloud agent
  * @param verityInstance  verity instance to which it talks to
  */
case class SdkConfig(sdkTypeStr: String,
                     name: String,
                     version: Option[String],
                     port: Option[Int],
                     endpointStr: Option[String],
                     domainDid: Option[String],
                     agentVerkey: Option[String],
                     keySeed: Option[String],
                     receiveSpacing: Option[Duration],
                     verityInstance: VerityInstance) {
  val sdkType: SdkType = SdkType.fromString(sdkTypeStr)

  val endpoint: Option[UrlParam] = {
    endpointStr match {
      case Some(ep) => Some(UrlParam(ep))
      case None     => port.map(p => UrlParam(s"localhost:$p"))
    }
  }

  override def toString: String = {
    s"[$name] version:$version - port:$port - endpoint:$endpoint - keySeed:$keySeed - agentVerkey:$agentVerkey"
  }
}

case class LedgerConfig(genesisFilePath: String,
                        submitterDID: DidStr,
                        submitterSeed: String,
                        submitterRole: String,
                        timeout: Int,
                        extendedTimeout: Int,
                        protocolVersion: Int) {

  override def toString: String = {
    val shortGenFile = {
      if (genesisFilePath.contains("scalatest-runs")) {
        genesisFilePath.substring(genesisFilePath.indexOf("scalatest-runs"))
      }
      else {
        genesisFilePath
      }
    }
    s"[ledger] genesisFile: ../$shortGenFile - submitterDID:$submitterDID - submitterSeed:$submitterSeed - submitterRole:$submitterRole"
  }
}

case class IntegrationTestEnv(sdks: Set[SdkConfig],
                              verityInstances: Set[VerityInstance],
                              ledgerConfig: LedgerConfig,
                              seedConflictCheck: Boolean,
                              timeout: Option[Duration] = None) {

  def isAnyRemoteInstanceExists: Boolean = verityInstances.exists(_.listeningPort.isEmpty)
  def isAnyLocalInstanceExists: Boolean = verityInstances.exists(_.setup)
  def allLocalInstances: Set[VerityInstance] = verityInstances.filter(_.setup)

  def instance_!(name: String): VerityInstance = {
    verityInstances.find(_.name == name).getOrElse(
      throw new RuntimeException(s"instance not found: $name"))
  }

  def sdk_!(name: String): SdkConfig = {
    sdks.find(_.name == name).getOrElse(
      throw new RuntimeException(s"edge agent not found: $name"))
  }

  val instanceList = verityInstances.toList

  if (containsDuplicates(instanceList.flatMap(_.listeningPort))) {
    throw new RuntimeException(s"'$VERITY_INSTANCE_CONFIGS' should have unique listening ports")
  }

  if (containsDuplicates(instanceList.map(_.endpoint))) {
    throw new RuntimeException(s"'$VERITY_INSTANCE_CONFIGS' should have unique endpoints")
  }

  val sdksList = sdks.toList

  if (containsDuplicates(sdksList.flatMap(_.port))) {
    throw new RuntimeException(s"'$SDK_CONFIGS' should have unique listening ports")
  }

  if (containsDuplicates(sdksList.map(_.endpoint))) {
    throw new RuntimeException(s"'$SDK_CONFIGS' should have unique endpoints")
  }

  val allLocalEndpoints = allLocalInstances.map(_.endpoint) ++ sdks.map(_.endpoint)
  if (allLocalInstances.size + sdks.size != allLocalEndpoints.size) {
    throw new RuntimeException(s"Local '$VERITY_INSTANCE_CONFIGS' and '$SDK_CONFIGS' " +
      s"should have unique endpoints")
  }

  if (isAnyRemoteInstanceExists) {
    allLocalInstances.foreach { vi =>
      if (vi.endpoint.isLocalhost) {
        throw new RuntimeException("at least one verity instance is remote, " +
          s"but '${vi.name}' instance's '$ENDPOINT' (provided or default: ${vi.endpoint}) " +
          s"doesn't seem to be able to accessible from any of those remote instance(s)")
      }
    }
  }

  //checking of 'isAnyRemoteInstanceExists' helps to determine if it is pointing to non local ledger or not
  if (isAnyRemoteInstanceExists && seedConflictCheck && allLocalInstances.exists(_.seed.isDefined)) {
    throw new RuntimeException("there are local instances with seed configured, " +
      "set 'seed-conflict-check' configuration to 'false' AFTER reading it's description")
  }

  sdks.foreach { ea =>
    if (! ea.verityInstance.isRunningLocally && ea.endpoint.exists(_.isLocalhost)) {
      throw new RuntimeException(s"'${ea.name}' edge-agent's '$ENDPOINT' " +
        s"(either default or provided: ${ea.endpoint}) " +
        s"doesn't seem to be reachable by it's verity instance '${ea.verityInstance.name}'")
    }
  }

  /**
    * environment variable needed to run any instance of this test environment
    * @return
    */
  lazy val commonEnvVars: List[EnvVar] = {
    val gfpEnvVar = Option(EnvVar("GENESIS_TXN_FILE_LOCATION", s"${ledgerConfig.genesisFilePath}"))

    val urlMapperEnvVar = verityInstances.find(_.appType.isType(AppInstance.Consumer)).map { cas =>
      EnvVar("URL_MAPPER_SERVICE_PORT", s"${cas.endpoint.port}")
    }

    val temp = Some(EnvVar("REQUIRE_SPONSOR", "false"))

    (gfpEnvVar ++ urlMapperEnvVar ++ temp).toList
  }

  allLocalInstances.foreach { lvi =>
    val envVarsToCheck = lvi.specificEnvVars.filter(_.uniqueValueAcrossEnv)
    allLocalInstances.filter(_.name != lvi.name).foreach { olvi =>
      val otherInstanceEnvVar = olvi.specificEnvVars.filter(_.uniqueValueAcrossEnv)
      envVarsToCheck.foreach { ev =>
        if (otherInstanceEnvVar.exists(oev => oev.name == ev.name && oev.value == ev.value)) {
          throw new RuntimeException(s"environment variable '${ev.name}' is configured to be unique across local verity instances, " +
            s"but it's randomly assigned value '${ev.value}' found to be same in '${lvi.name}' and '${olvi.name}', " +
            s"try to re-run test and it should most probably work")
        }
      }
    }
  }

  /**
    * provides a modified config to reflect correct genesis txn file location
    * @return
    */
  def config: AppConfig = {
    val conf = ConfigFactory.load()
    val updatedConf = if (isAnyRemoteInstanceExists) {
      Option(
        conf.withValue(
          LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION,
          ConfigValueFactory.fromAnyRef(
            ledgerConfig.genesisFilePath
          )
        )
      )
    } else Option(conf)
    new TestAppConfig(updatedConf)
  }

  override def toString: String = {
    val sdksStr = sdks.map(ea => s"     ${ea.toString}").mkString("\n")
    val verityInstancesStr = verityInstances.map(vi => s"     ${vi.toString}").mkString("\n")

    s"\n<<< test-environment-configuration >>>" + "\n\n" +
      s"  SDKs:\n$sdksStr" + "\n\n" +
      s"  verity-instances:\n$verityInstancesStr" + "\n\n" +
      s"  ledger-config:\n     " + ledgerConfig
  }
}

case class EnvVar(name: String, value: Any, uniqueValueAcrossEnv: Boolean=false)
