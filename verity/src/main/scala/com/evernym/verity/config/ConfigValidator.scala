package com.evernym.verity.config

import java.nio.file.{Files, Paths}

import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status._
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.actor.resourceusagethrottling.helper._
import com.evernym.verity.util.SubnetUtilsExt._
import com.evernym.verity.util._
import com.evernym.verity.Exceptions
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config._
import com.typesafe.scalalogging.Logger

import scala.collection.JavaConverters._


trait ConfigReaderHelper {

  def config: Config

  private def readReqConfig[T](f: String => T, key: String): T = {
    try {
      f(key)
    } catch {
      case _: Missing =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found: $key"))
      case _: ConfigException =>
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config not found or has invalid value: $key"))
    }
  }

  private def readOptionalConfig[T](f: String => T, key: String): Option[T] = {
    try {
      Option(f(key))
    } catch {
      case _: Missing => None
    }
  }

  def getConfigStringReq(key: String): String = {
    readReqConfig(config.getString, key)
  }

  def getConfigStringOption(key: String): Option[String] = {
    readOptionalConfig(config.getString, key)
  }

  def getConfigListOfStringReq(key: String): List[String] = {
    readReqConfig(config.getStringList, key).asScala.toList.map(_.trim)
  }

  def getConfigListOfStringOption(key: String): Option[List[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getConfigSetOfStringReq(key: String): Set[String] = {
    readReqConfig(config.getStringList, key).asScala.map(_.trim).toSet
  }

  def getConfigSetOfStringOption(key: String): Option[Set[String]] = {
    readOptionalConfig(config.getStringList, key).map(_.asScala.map(_.trim).toSet)
  }

  def getConfigIntReq(key: String): Int = {
    readReqConfig(config.getInt, key)
  }

  def getConfigIntOption(key: String): Option[Int] = {
    readOptionalConfig(config.getInt, key)
  }

  def getConfigLongReq(key: String): Long = {
    readReqConfig(config.getLong, key)
  }

  def getConfigLongOption(key: String): Option[Long] = {
    readOptionalConfig(config.getLong, key)
  }

  def getConfigDoubleReq(key: String): Double = {
    readReqConfig(config.getDouble, key)
  }

  def getConfigDoubleOption(key: String): Option[Double] = {
    readOptionalConfig(config.getDouble, key)
  }

  def getConfigBooleanOption(key: String): Option[Boolean] = {
    readOptionalConfig(config.getBoolean, key)
  }

  def getConfigBooleanReq(key: String): Boolean = {
    readReqConfig(config.getBoolean, key)
  }

  //helper methods to load from different config
  def getConfigStringReq(fromConfig: Config,key: String): String = {
    readReqConfig(fromConfig.getString, key)
  }

  def getConfigStringOption(fromConfig: Config,key: String): Option[String] = {
    readOptionalConfig(fromConfig.getString, key)
  }

  def getConfigBooleanOption(fromConfig: Config,key: String): Option[Boolean] = {
    readOptionalConfig(fromConfig.getBoolean, key)
  }

  def getConfigIntReq(fromConfig: Config, key: String): Int = {
    readReqConfig(fromConfig.getInt, key)
  }

  def getConfigIntOption(fromConfig: Config, key: String): Option[Int] = {
    readOptionalConfig(fromConfig.getInt, key)
  }

  def getConfigLongReq(fromConfig: Config, key: String): Long = {
    readReqConfig(fromConfig.getLong, key)
  }

  def getConfigLongOption(fromConfig: Config, key: String): Option[Long] = {
    readOptionalConfig(fromConfig.getLong, key)
  }

  def getConfigListOfStringReq(fromConfig: Config, key: String): List[String] = {
    readReqConfig(fromConfig.getStringList, key).asScala.toList.map(_.trim)
  }

  def getConfigListOfStringOption(fromConfig: Config, key: String): Option[List[String]] = {
    readOptionalConfig(fromConfig.getStringList, key).map(_.asScala.toList.map(_.trim))
  }

  def getConfigSetOfStringReq(fromConfig: Config, key: String): Set[String] = {
    getConfigListOfStringReq(fromConfig, key).toSet
  }

  def getConfigOption(key: String): Option[Config] = {
    readOptionalConfig(config.getConfig, key)
  }

  def getConfigOption(fromConfig: Config, key: String): Option[Config] = {
    readOptionalConfig(fromConfig.getConfig, key)
  }

  def getObjectListOption(key: String): Option[Seq[ConfigObject]] = {
    readOptionalConfig(config.getObjectList, key)
      .map(_.asScala)
  }
}

class ConfigReadHelper(val config: Config) extends ConfigReaderHelper

trait ConfigValidator extends ConfigReaderHelper {

  val logger: Logger = getLoggerByClass(classOf[ConfigValidator])

  def validationType: String

  def validateConfig(): Unit

  def performConfigValidation(): Unit = {
    logger.debug(validationType + " started...")
    validateConfig()
    logger.debug(validationType + " finished")
  }

  def getValidationFailedExc(origin: ConfigOrigin, path: String, problem: String): ConfigLoadingFailedException = {
    val sb = new StringBuilder
    sb.append("origin: ")
    sb.append(origin.description)
    sb.append(", path: ")
    sb.append(path)
    sb.append(", error: ")
    sb.append(problem)
    new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(sb.toString()))
  }
}

//checks for invalid values (like file path without a file etc) or missing values
class ConfigValueValidator (val config: Config) extends ConfigValidator {

  override val validationType: String = "general configuration validation"

  override def validateConfig(): Unit = {

    def validateConfigs(atPath: Option[String] = None): Unit = {
      val confsToBeSearched = atPath.map { path =>
        config.withOnlyPath(path)
      }.getOrElse(config)

      confsToBeSearched.entrySet().asScala.foreach { es =>
        if (es.getValue.render().contains("TODO:")) {
          if (es.getValue.valueType() == ConfigValueType.OBJECT) {
            val newPath = Option(atPath.map(_ + ".").getOrElse("") + es.getKey)
            validateConfigs(newPath)
          } else {
            val errMsg = s"invalid config value found: ${es.getValue.render()}"
            throw getValidationFailedExc(es.getValue.origin(), es.getKey, errMsg)
          }
        }
      }
    }

    def checkIfFileExistIfConfigured(confName: String): Unit = {
      getConfigStringOption(confName).foreach { path =>
        if (! Files.exists(Paths.get(path))) {
          val cv = config.getValue(confName)
          val problem = s"no file exists at given path '$path' for config: $confName"
          throw getValidationFailedExc(cv.origin(), confName, problem)
        }
      }
    }


    def checkIfResourceExistIfConfigured(confName: String): Unit = {
      getConfigStringOption(confName).foreach { path =>
        if (! Files.exists(Paths.get(path))) {
          val cv = config.getValue(confName)
          val problem = s"no file exists at given path '$path' for config: $confName"
          throw getValidationFailedExc(cv.origin(), confName, problem)
        }
      }
    }

    def validateAllowedCIDRNotationIpAddresses(): Unit = {
      val confNames = List(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES, SMS_SVC_ALLOWED_CLIENT_IP_ADDRESSES)
      confNames.foreach { cn =>
        try {
          getConfigListOfStringOption(cn).getOrElse(List.empty).foreach(ip => new SubnetUtilsExt(ip))
        } catch {
          case _: IllegalArgumentException =>
            val cv = config.getValue(cn)
            val problem = s"unable to parse value '${cv.unwrapped()}' for config: $cn"
            throw getValidationFailedExc(cv.origin(), cn, problem)
        }
      }
    }

    def validateAgencyConfigs(): Unit = {
      validateConfigs(Option(VERITY))
      validateAllowedCIDRNotationIpAddresses()
      List(LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION, LIB_INDY_LIBRARY_DIR_LOCATION).foreach { confName =>
        checkIfFileExistIfConfigured(confName)
      }
      checkIfResourceExistIfConfigured(KEYSTORE_LOCATION)
    }

    def validateOtherConfigs(): Unit = {
      validateConfigs(Option("akka"))
      validateConfigs(Option("kamon.environment"))
    }

    validateAgencyConfigs()
    validateOtherConfigs()
  }

}

case class DepConfDetail(confName: String, confValue: Option[String] = None, caseSensitive: Boolean=true)

case class ConfDetail(confName: String,
                      allowedValues: Set[String] = Set.empty,
                      unAllowedValues: Set[String] = Set.empty,
                      depConfDetail: Option[DepConfDetail] = None)



trait ConfigValidatorBase extends ConfigValidator {
  def configsToBeValidated(): Set[ConfDetail]
  def required: Boolean

  def getOptConf(confName: String): Option[String] = {
    try {
      Option(config.getAnyRef(confName).toString)
    } catch {
      case _: Missing => None
    }
  }

  def checkConf(required: Boolean, confName: String, allowedValues: Set[String] = Set.empty,
                unAllowedValues: Set[String] = Set.empty): Unit = {
    try {
      val av = config.getAnyRef(confName)
      if (allowedValues.nonEmpty && ! allowedValues.contains(av.toString)) {
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option("config value is not as expected " +
          s"(config name: '$confName', actual conf value: '$av', allowed values: '${allowedValues.mkString(", ")}')"))
      }

      if (unAllowedValues.nonEmpty && unAllowedValues.contains(av.toString)) {
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option("config value is not as expected " +
          s"(config name: '$confName', actual conf value: '$av', un allowed values: '${unAllowedValues.mkString(", ")}' " +
          "[NOTE: it might be default value which you don't see in your configuration, " +
          "in that case just add this new configuration with some value other than un allowed values])"))
      }

    } catch {
      case _: Missing if required =>
        val ev = if (allowedValues.nonEmpty) s" (allowed values: ${allowedValues.mkString(", ")})" else ""
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"required config missing: $confName$ev"))
    }
  }

  override def validateConfig(): Unit = {

    configsToBeValidated().foreach { c =>
      c.depConfDetail match {
        case Some(dc: DepConfDetail) =>
          val actualDepConfValue = getOptConf(dc.confName)
          val givenDepConfValue = dc.confValue
          (actualDepConfValue, givenDepConfValue) match {
            case (Some(_), None) =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case (Some(adcv), Some(gdcv)) if dc.caseSensitive && adcv == gdcv =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case (Some(adcv), Some(gdcv)) if ! dc.caseSensitive && adcv.toUpperCase == gdcv.toUpperCase =>
              checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
            case _ => //nothing to do as dependent conf itself is not present
          }
        case None =>
          checkConf(required, c.confName, c.allowedValues, c.unAllowedValues)
      }
    }
  }
}

trait ReqConfigValidator extends ConfigValidatorBase  {
  override val validationType: String = "required configuration validation"
  override val required = true
}

trait OptionalConfigValidator extends ConfigValidatorBase  {
  override val validationType: String = "optional configuration validation"
  override val required = false
}

trait StaleConfigValidator extends ConfigValidator {

  override val validationType: String = "stale configuration checking"

  def staleConfigs: Set[String]

  override def validateConfig(): Unit = {
    staleConfigs.foreach { sc =>
      try {
        val cv = config.getValue(sc)
        val staleConfigDetail =
          "file name: " + cv.origin.filename +
            ", line no: " + cv.origin.lineNumber +
            ", path: " + sc
        logger.warn("stale configuration found (it can be removed)", (LOG_KEY_ERR_MSG, staleConfigDetail))
      } catch {
        case _: Missing =>
      }
    }
  }
}

trait CommonStaleConfigValidatorBase extends StaleConfigValidator {
  override def staleConfigs: Set[String] = Set (
    "agency.config",
    "agency.routing",
    "agency.services.sms-service.external-services.twilio.endpoint",
    "agency.lib-indy.library-file-location",
    "agency.lib-indy.ledger.wallet-type"
  )
}

trait CommonReqConfigValidatorBase extends ReqConfigValidator {

  override val validationType: String = "required configuration validation"
  override val required = true

  override def configsToBeValidated(): Set[ConfDetail] =
    commonConfigsToBeValidated ++ conditionalConfigsToBeValidated

  def conditionalConfigsToBeValidated: Set[ConfDetail] = {
    if (getConfigBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {
      Set (
        ConfDetail(PUSH_NOTIF_GENERAL_MSG_TITLE_TEMPLATE),
        ConfDetail(PUSH_NOTIF_DEFAULT_LOGO_URL),
        ConfDetail(PUSH_NOTIF_DEFAULT_SENDER_NAME),
        ConfDetail(CONNECT_ME_MAPPED_URL_TEMPLATE)
      )
    } else Set.empty
  }

  def commonConfigsToBeValidated(): Set[ConfDetail] = Set (
    ConfDetail(VERITY_DOMAIN_URL_PREFIX),
    ConfDetail(VERITY_ENDPOINT_HOST),
    ConfDetail(VERITY_ENDPOINT_PORT),
    ConfDetail(VERITY_ENDPOINT_PATH_PREFIX),

    ConfDetail(HTTP_INTERFACE),
    ConfDetail(HTTP_PORT),

    ConfDetail(KEYSTORE_LOCATION, depConfDetail=Option(DepConfDetail(HTTP_SSL_PORT))),
    ConfDetail(KEYSTORE_PASSWORD, depConfDetail=Option(DepConfDetail(KEYSTORE_LOCATION))),

    ConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST),
    ConfDetail(URL_MAPPER_SVC_ENDPOINT_PORT, depConfDetail=Option(DepConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST))),
    ConfDetail(URL_MAPPER_SVC_ENDPOINT_PATH_PREFIX, depConfDetail=Option(DepConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST))),

    ConfDetail(SMS_SVC_SEND_VIA_LOCAL_AGENCY),
    ConfDetail(SMS_SVC_ENDPOINT_HOST, depConfDetail=Option(DepConfDetail(SMS_SVC_SEND_VIA_LOCAL_AGENCY, Option(NO)))),
    ConfDetail(SMS_SVC_ENDPOINT_PORT, depConfDetail=Option(DepConfDetail(SMS_SVC_ENDPOINT_HOST))),
    ConfDetail(SMS_SVC_ENDPOINT_PATH_PREFIX, depConfDetail=Option(DepConfDetail(SMS_SVC_ENDPOINT_HOST))),

    ConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, depConfDetail=Option(DepConfDetail(SMS_SVC_SEND_VIA_LOCAL_AGENCY, Option(YES)))),

    ConfDetail(SMS_SVC_ALLOWED_CLIENT_IP_ADDRESSES, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER))),

    ConfDetail(TWILIO_TOKEN, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_ACCOUNT_SID, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_DEFAULT_NUMBER, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_APP_NAME, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),

    ConfDetail(OPEN_MARKET_USER_NAME, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),
    ConfDetail(OPEN_MARKET_PASSWORD, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),
    ConfDetail(OPEN_MARKET_SERVICE_ID, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),

    ConfDetail(LIB_INDY_LIBRARY_DIR_LOCATION),
    ConfDetail(LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION),
    ConfDetail(LIB_INDY_LEDGER_POOL_NAME),
    ConfDetail(LIB_INDY_WALLET_TYPE, allowedValues = Set(WALLET_TYPE_DEFAULT, WALLET_TYPE_MYSQL)),

    ConfDetail(SALT_WALLET_NAME),
    ConfDetail(SALT_WALLET_ENCRYPTION),
    ConfDetail(SALT_EVENT_ENCRYPTION),

    ConfDetail(SECRET_ROUTING_AGENT),
    ConfDetail(SECRET_URL_STORE),
    ConfDetail(SECRET_KEY_VALUE_MAPPER),
    ConfDetail(SECRET_TOKEN_TO_ACTOR_ITEM_MAPPER),
    ConfDetail(SECRET_RESOURCE_WARNING_STATUS_MNGR),
    ConfDetail(SECRET_RESOURCE_BLOCKING_STATUS_MNGR),

    ConfDetail(SMS_MSG_TEMPLATE_INVITE_URL),
    ConfDetail(SMS_MSG_TEMPLATE_OFFER_CONN_MSG),

    ConfDetail(CONN_REQ_MSG_EXPIRATION_TIME_IN_SECONDS),

    ConfDetail(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES),

    ConfDetail(KAMON_ENV_HOST, unAllowedValues = Set("auto")),
    ConfDetail(KAMON_PROMETHEUS_START_HTTP_SERVER, allowedValues = Set("no")),

    ConfDetail(AKKA_SHARDING_REGION_NAME_USER_AGENT),
    ConfDetail(AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE),

    ConfDetail(WALLET_STORAGE_READ_HOST, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_WRITE_HOST, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_HOST_PORT, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_CRED_USERNAME, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_CRED_PASSWORD, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_DB_NAME, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),

    ConfDetail(AKKA_MNGMNT_HTTP_HOSTNAME,
      depConfDetail = Option(DepConfDetail(AKKA_MNGMNT_HTTP_ENABLED, Option(YES), caseSensitive=false)),
      unAllowedValues = Set("<hostname>")),
    ConfDetail(AKKA_MNGMNT_HTTP_PORT, depConfDetail = Option(DepConfDetail(AKKA_MNGMNT_HTTP_ENABLED, Option(YES), caseSensitive=false))),
    ConfDetail(AKKA_MNGMNT_HTTP_API_CREDS, depConfDetail = Option(DepConfDetail(AKKA_MNGMNT_HTTP_ENABLED, Option(YES), caseSensitive=false))),

    ConfDetail("akka.actor.serializers.protoser", allowedValues = Set("com.evernym.verity.actor.serializers.ProtoBufSerializer")),
    ConfDetail("akka.actor.serializers.kryo-akka", allowedValues = Set("com.twitter.chill.akka.AkkaSerializer")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedStateMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedMultiEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.PersistentMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.PersistentMultiEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.ActorMessage\"", allowedValues = Set("kryo-akka"))
  )
}

trait CommonOptionalConfigValidatorBase extends OptionalConfigValidator {

  override def configsToBeValidated(): Set[ConfDetail] = Set.empty

}

class CommonReqConfigValidator (val config: Config) extends CommonReqConfigValidatorBase

class CommonOptionalConfigValidator (val config: Config) extends CommonOptionalConfigValidatorBase

class CommonStaleConfigValidator (val config: Config) extends CommonStaleConfigValidatorBase

class ResourceUsageRuleConfigValidator(val config: Config) extends ConfigValidator {
  override val validationType: String = "resource-usage-rule config validation"

  override def validateConfig(): Unit =  {
    val rurc = buildResourceUsageRules()
    validateResourceUsageRuleConfig(rurc)
  }

  def getSorted(c: Config, key: String): Seq[(String, ConfigValue)] = {
    c.getObject(key).asScala.map { case (k, _) =>
      try {
        k.toInt
      } catch {
        case _: NumberFormatException =>
          throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option("non numeric character found in " +
            "one of the child of parent key $key: $k"))
      }
    }
    c.getObject(key).asScala.toSeq.sortWith(_._1.toInt < _._1.toInt)
  }

  private def getResourceUsageBucketRules(c: Config, key: String): Map[Int, BucketRule] = {
    getSorted(c, key).map { case (k, _) =>
      val allowedCounts = getConfigIntReq(c, s"$key.$k.allowed-counts")
      val violationActionId = getConfigStringReq(c, s"$key.$k.violation-action-id")
      val persistUsageState = getConfigBooleanOption(c, s"$key.$k.persist-usage-state").getOrElse(false)
      k.toInt -> BucketRule(allowedCounts, violationActionId, persistUsageState)
    }.toMap
  }

  private def getResourceUsageRules(c: Config, key: String): Map[String, ResourceUsageRule] = {
    c.getObject(key).asScala.map { case (k, _) =>
      k -> ResourceUsageRule(getResourceUsageBucketRules(c, s"$key.$k"))
    }.toMap
  }

  private def getResourceTypeUsageRules(c: Config, key: String): Map[String, ResourceTypeUsageRule] = {
    val r = c.getObject(key).asScala.map { case (k, _) =>
      k -> ResourceTypeUsageRule(getResourceUsageRules(c, s"$key.$k"))
    }.toMap
    val resourceNameSummary = r.flatMap(_._2.resourceUsageRules.keys.filterNot(_ == "default")).groupBy(identity).mapValues(_.size).filter(_._2 > 1)
    if (resourceNameSummary.nonEmpty) {
      throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
        Option(s"duplicate resource names found: '${resourceNameSummary.keySet.mkString}'"))
    }
    r
  }

  private def getUsageRules(c: Config, key: String): Map[String, UsageRule] = {
    c.getObject(key).asScala.map { case (k, _) =>
      k -> UsageRule(getResourceTypeUsageRules(c, s"$key.$k"))
    }.toMap
  }

  private def getInstructionDetail(c: Config, key: String): Map[String, Any] = {
    c.getObject(key).asScala.map { case (k, v) =>
      k -> v.unwrapped()
    }.toMap
  }

  private def getViolationActionRules(c: Config, key: String): Map[String, ViolationActions] = {
    getSorted(c, key).map { case (k, _) =>
      val mp = c.getObject(s"$key.$k").asScala.map { case (k1, _) =>
        k1 -> InstructionDetail(getInstructionDetail(c, s"$key.$k.$k1"))
      }.toMap
      k -> ViolationActions(mp)
    }.toMap
  }

  private def getRulesToTokens(c: Config, key: String): Map[String, Set[String]] = {
    c.getObject(key).asScala.map { case (k, _) =>
      val tokens = getConfigSetOfStringReq(c, s"$key.$k")
      k -> tokens
    }.toMap
  }

  def validateActionRules(c: ResourceUsageRuleConfig): Unit = {
    val aev = new UsageViolationActionExecutorValidator
    c.actionRules.foreach { case (k, vr) =>
      vr.instructions.foreach { case (insName, insDetail )=>
        aev.instructions.find(_.name == insName) match {
          case Some(i: Instruction) =>
            i.validateDetail(k, insDetail)
            val reqKeys = i.validators.map(_.keyName)
            val actualKeys = insDetail.detail.keySet
            val extraKeys = actualKeys.diff(reqKeys)
            if (extraKeys.nonEmpty) {
              throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
                Option(s"'$VIOLATION_ACTION.$k.$insName' instruction contains extra keys: ${extraKeys.mkString(", ")}"))
            }
          case None=>
            throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
              Option(s"unsupported instruction '$insName' found under '$VIOLATION_ACTION.$k'"))
        }
      }
    }
  }

  def validateRuleToTokens(c: ResourceUsageRuleConfig): Unit = {
    val tokenCharsetRegex = c.tokenCharsetRegex
    val ipCheckRegex = c.ipCheckRegex
    val usageRuleNames = c.usageRules.keySet
    var allTokens = Set.empty[String]
    c.rulesToTokens.foreach { case (name, tokens) =>
      if (! usageRuleNames.contains(name)) {
        val problem = s"rule name '$RULE_TO_TOKENS.$name' not defined under '$USAGE_RULES'"
        throw getValidationFailedExc(config.getValue(RULE_TO_TOKENS).origin(), RULE_TO_TOKENS, problem)
      }
      val tokenToMultiRules = allTokens.intersect(tokens)
      if (allTokens.intersect(tokens).nonEmpty) {
        val problem = "one token can be assigned to only one rule, " +
          s"tokens '${tokenToMultiRules.mkString(", ")}' are assigned to more than one rules"
        throw getValidationFailedExc(config.getValue(RULE_TO_TOKENS).origin(), RULE_TO_TOKENS, problem)
      } else {
        allTokens = allTokens ++ tokens
      }
      tokens.foreach(t => checkIfValidToken(t, tokenCharsetRegex, ipCheckRegex, s"$RULE_TO_TOKENS.$name"))
    }
    validateRuleToTokenConflicts(RULE_TO_TOKENS, c.rulesToTokens)
  }

  def checkIfValidToken(token: String, tokenCharsetRegex: Option[String], ipCheckRegex:Option[String],
                        confName: String): Unit = {
    val validToken: Boolean = isValidToken(token, tokenCharsetRegex, ipCheckRegex)
    if (! validToken) {
      val problem = s"invalid token: $token"
      val configOrigin = config.getValue(confName).origin()
      throw getValidationFailedExc(configOrigin, confName, problem)
    }
  }

  def isValidToken(token: String, tokenCharsetRegex: Option[String] = None,
                   ipCheckRegex:Option[String] = None): Boolean = {
    val charSetRegex = tokenCharsetRegex.getOrElse("[a-zA-Z0-9-./]*")
    val IPCheckRegex = ipCheckRegex.getOrElse("(\\d+.\\d+.\\d+.\\d+){1}(\\/+\\w*)")

    (token.matches(charSetRegex), token.matches(IPCheckRegex)) match {
      case (true, true) => isSupportedIPAddress(token)
      case (true, false) => true
      case (false, _) => false
    }
  }

  def validateRuleToTokenConflicts(confName: String, rulesToTokens: Map[String, Set[String]]): Unit = {
    rulesToTokens.foreach { case (rule, tokens) =>
      rulesToTokens.filterNot(_._1 == rule).foreach{ case (otherRule, otherTokens) =>
        validatedIpRangeConflicts(
          TokensToValidate(confName + "." + rule, tokens),
          TokensToValidate(confName + "." + otherRule, otherTokens))
      }
    }
  }

  case class TokensToValidate(confName: String, tokens: Set[String])

  def validatedIpRangeConflicts(tokens: TokensToValidate, otherTokens: TokensToValidate):Unit = {
    val filteredOtherTokens = otherTokens.tokens.filter(isSupportedIPAddress)
    tokens.tokens.filter(isSupportedIPAddress).foreach { token =>
      val subnetUtil = getSubnetUtilsExt(token)
      filteredOtherTokens.foreach { otherToken =>
        val otherSubnetUtil = getSubnetUtilsExt(otherToken)
        if (subnetUtil.isIpRangeConflicting(otherSubnetUtil)) {
          val problem = s"ip range for token: $token (${tokens.confName}) " +
           s"and token: $otherToken (${otherTokens.confName}) is conflicting (two rules can not be applied on one ip address)"
          throw getValidationFailedExc(config.getValue(tokens.confName).origin(), tokens.confName, problem)
        }
      }
    }
  }

  def validateUsageRules(c: ResourceUsageRuleConfig): Unit = {
    c.usageRules.values.foreach { ur =>
      ur.resourceTypeUsageRules.values.foreach { rtur =>
        rtur.resourceUsageRules.values.foreach { rur =>
          rur.bucketRules.values.foreach { br =>
            if (! c.actionRules.keySet.contains(br.violationActionId)) {
              throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"'$VIOLATION_ACTION' config " +
                s"missing object with key: ${br.violationActionId}"))
            }
          }
        }
      }
    }
  }

  def validateBlacklistedAndWhitelistedTokens(c: ResourceUsageRuleConfig): Unit = {
    val commonTokens = c.whitelistedTokens.intersect(c.blacklistedTokens)
    val tokenCharsetRegex = c.tokenCharsetRegex
    val ipCheckRegex = c.tokenCharsetRegex
    if (commonTokens.nonEmpty) {
      val problem = s"whitelisted-tokens and blacklisted-tokens config can't contain same tokens: ${commonTokens.mkString(", ")}"
      throw getValidationFailedExc(config.getValue(WHITELISTED_TOKENS).origin(), WHITELISTED_TOKENS, problem)
    }
    c.whitelistedTokens.foreach(t => checkIfValidToken(t, tokenCharsetRegex, ipCheckRegex, WHITELISTED_TOKENS))
    c.blacklistedTokens.foreach(t => checkIfValidToken(t, tokenCharsetRegex, ipCheckRegex, BLACKLISTED_TOKENS))
    validatedIpRangeConflicts(TokensToValidate(WHITELISTED_TOKENS, c.whitelistedTokens),
      TokensToValidate(BLACKLISTED_TOKENS, c.blacklistedTokens))
  }

  def validateResourceUsageRuleConfig(c: ResourceUsageRuleConfig): Unit = {
    validateUsageRules(c)
    validateActionRules(c)
    validateRuleToTokens(c)
    validateBlacklistedAndWhitelistedTokens(c)
  }

  def getBoolean(featureRootConfig:  Config, key: String): Boolean = {
    try { featureRootConfig.getBoolean(key) }
    catch { case _: Missing => false }
  }

  def buildResourceUsageRules(): ResourceUsageRuleConfig = {
    val rurc = try {

      val apiUsageRulesConfig = config.getConfig(RESOURCE_USAGE_RULES)

      val applyUsageRules = getBoolean(apiUsageRulesConfig, "apply-usage-rules")

      val persistAllUsageStates = getBoolean(apiUsageRulesConfig, "persist-all-usage-states")

      val snapshotAfterEvents = getConfigIntOption(apiUsageRulesConfig,"snapshot-after-events").getOrElse(200)

      val ruleToTokens =
        try { getRulesToTokens(apiUsageRulesConfig, "rule-to-tokens") }
        catch { case _: Missing => Map.empty[String, Set[String]] }
      val blacklisted =
        try { getConfigSetOfStringReq(apiUsageRulesConfig, "blacklisted-tokens") }
        catch { case _: Missing => Set.empty[String] }
      val whitelisted =
        try { getConfigSetOfStringReq(apiUsageRulesConfig, "whitelisted-tokens") }
        catch { case _: Missing => Set.empty[String] }
      val rules =
        try { getUsageRules(apiUsageRulesConfig, "usage-rules") }
        catch { case _: Missing => Map.empty[String, UsageRule] }
      val actionRules =
        try { getViolationActionRules(apiUsageRulesConfig, "violation-action") }
        catch { case _: Missing => Map.empty[String, ViolationActions] }
      val tokenCharsetRegex = try { Option(apiUsageRulesConfig.getString("token-charset-regex")) }
        catch { case _: Missing => None }
      val ipCheckRegex = try { Option(apiUsageRulesConfig.getString("ip-check-regex")) }
        catch { case _: Missing => None }

      ResourceUsageRuleConfig(applyUsageRules, persistAllUsageStates, snapshotAfterEvents,
        rules, ruleToTokens, blacklisted, whitelisted, actionRules, tokenCharsetRegex, ipCheckRegex)
    } catch {
      case _: Missing =>
        ResourceUsageRuleConfig(applyUsageRules = false, persistAllBucketUsages = false, 100,
          Map.empty, Map.empty, Set.empty, Set.empty, Map.empty)
    }
    rurc
  }

}

class AkkaMngmntApiConfigValidator(val config: Config) extends ConfigValidator {

  override val validationType: String = s"$AKKA_MNGMNT_HTTP config validation"

  override def validateConfig(): Unit =  {
    val isEnabled = try {
      Option(config.getString(AKKA_MNGMNT_HTTP_ENABLED)).map(_.toUpperCase).contains(YES)
    } catch {
      case _: Missing => false
    }
    if (isEnabled) {
      config.getObjectList(AKKA_MNGMNT_HTTP_API_CREDS).asScala.foreach { acs =>
        Set("username", "password").foreach { ek =>
          try {
            acs.toConfig.getString(ek)
          } catch {
            case _ : Missing =>
              throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode, Option(s"'$AKKA_MNGMNT_HTTP_API_CREDS.$ek' config missing"))
          }
        }
      }
    }
  }

}

trait ConfigValidatorCreator {
  def create(config: Config): ConfigValidator
}

object ConfigValueValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new ConfigValueValidator(config)
}

object CommonReqConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new CommonReqConfigValidator(config)
}

object CommonOptionalConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new CommonOptionalConfigValidator(config)
}

object CommonStaleConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new CommonStaleConfigValidator(config)
}

object ResourceUsageRuleConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new ResourceUsageRuleConfigValidator(config)
}

object AkkaMngmntApiConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new AkkaMngmntApiConfigValidator(config)
}

object CommonConfigValidatorCreator {

  def baseValidatorCreators: List[ConfigValidatorCreator] = List(
    ConfigValueValidator,
    CommonStaleConfigValidator,
    ResourceUsageRuleConfigValidator,
    AkkaMngmntApiConfigValidator
  )

  def getAllValidatorCreators: List[ConfigValidatorCreator] =
    baseValidatorCreators ++
      List(CommonReqConfigValidator) ++
      List(CommonOptionalConfigValidator)
}

class ConfigValidatorHelper(givenConfig: Option[Config]=None) {

  val config: Config = givenConfig.getOrElse{
    try {
      ConfigFactory.load()
    } catch {
      case e: Exception =>
        throw new ConfigLoadingFailedException(CONFIG_LOADING_FAILED.statusCode, Option(s"error while loading config (${Exceptions.getErrorMsg(e)})"))
    }
  }

  def getAllValidators(validatorCreators: List[ConfigValidatorCreator]): List[ConfigValidator] = {
    validatorCreators.map(_.create(config))
  }
}