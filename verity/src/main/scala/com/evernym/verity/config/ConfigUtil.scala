package com.evernym.verity.config

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.metrics._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.ledger.TransactionAuthorAgreement
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.SponsorDetails
import com.evernym.verity.util.TAAUtil.taaAcceptanceDatePattern
import com.typesafe.config.{Config, ConfigException}
import com.typesafe.config.ConfigUtil.{joinPath, splitPath}
import org.apache.commons.lang3.StringUtils
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.concurrent.duration.{DAYS, Duration, DurationInt}
import scala.util.{Failure, Success, Try}

object ConfigUtil {
  val logger: Logger = LoggerFactory.getLogger(getClass)
  val MAX_RETENTION_POLICY: Long = 730

  /**
    * Finds the last segment of a fully qualified HOCON path.
    *
    * For example: "akka.sharding-region-name.user-agent" would
    * return "user-agent"
    *
    * @param keyPath
    * @return last segment of a HOCON path
    */
  def lastKeySegment(keyPath: String): String = {
    Option(keyPath)
      .orElse(throw new ConfigException.BadPath(keyPath, "path must not be null"))
      .map(splitPath)
      .map(_.asScala)
      .filter(_.nonEmpty) // filter empty lists from splitPath
      .map(_.last) // pull last segment in the path
      .getOrElse(throw new ConfigException.BadPath(keyPath, "path contains no segments"))
  }

  def parentKeySegment(keyPath: String): (String, String) = {
    Option(keyPath)
      .orElse(throw new ConfigException.BadPath(keyPath, "path must not be null"))
      .map(splitPath)
      .map(_.asScala)
      .filter(_.nonEmpty) // filter empty lists from splitPath
      .map{ seq =>
        if (seq.size == 1) {
          ("", seq.head)
        }
        else {
          (joinPath(seq.take(seq.size-1).asJava), seq.last)
        }
      }
      .getOrElse(throw new ConfigException.BadPath(keyPath, "path contains no segments"))
  }

  def isTAAConfigEnabled(appConfig: AppConfig): Boolean = {
    appConfig.getConfigBooleanOption(LIB_INDY_LEDGER_TAA_ENABLED).getOrElse(false)
  }

  def findTAAConfig(config: AppConfig, version: String): Option[TransactionAuthorAgreement] = {
    // Return None if the configuration file says TAA is disabled.
    if(!isTAAConfigEnabled(config)) {
      return None
    }

    try {
      Some(
        findTAAConfig_!(config, version)
      )
    }
    catch {
      case _: ConfigLoadingFailedException => None
    }
  }

  def findTAAConfig_!(config: AppConfig, version: String): TransactionAuthorAgreement = {
    val agreementVersionPath = s"${LIB_INDY_LEDGER_TAA_AGREEMENTS}.${com.typesafe.config.ConfigUtil.quoteString(version)}"

    TransactionAuthorAgreement(
      version,
      config.getConfigStringReq(s"$agreementVersionPath.digest").toLowerCase(),
      config.getConfigStringReq(s"$agreementVersionPath.mechanism"),
      config.getConfigStringReq(s"$agreementVersionPath.time-of-acceptance")
    )
  }

  def nowTimeOfAcceptance(): String = {
    DateTimeFormat
      .forPattern(taaAcceptanceDatePattern)
      .print(DateTime.now(DateTimeZone.UTC)
        .withTimeAtStartOfDay())
  }

  def sponsorRequired(appConfig: AppConfig): Boolean =
    appConfig
      .getConfigBooleanReq(s"$PROVISIONING.sponsor-required")

  private def findSponsorConfig(lookupKey: String, lookupValue: String, appConfig: AppConfig): Option[SponsorDetails] =
      appConfig
          .getObjectListOption(s"$PROVISIONING.sponsors")
        .flatMap { seq =>
          seq
            .find { x =>
              Try(x.toConfig.getString(lookupKey))
                .map(_.equals(lookupValue))
                .getOrElse(false)
            }
            .map {SponsorDetails(_)}
        }


  def findSponsorConfigWithName(name: String, appConfig: AppConfig): Option[SponsorDetails] =
    findSponsorConfig("name", name, appConfig)

  def findSponsorConfigWithId(id: String, appConfig: AppConfig): Option[SponsorDetails] =
    findSponsorConfig("id", id, appConfig)

  def findAgentSpecificConfig(key: String, domainId: Option[DomainId], appConfig: AppConfig): String = {
    val default = appConfig.getConfigStringReq(key)
    domainId match {
      case Some(domain) =>
        val (parent, targetKey) = parentKeySegment(key)
        val specificKey = {
          if (StringUtils.isBlank(parent)) s"agent-specific.$domain.$targetKey"
          else s"$parent.agent-specific.$domain.$targetKey"
        }
        val specific = appConfig.getConfigStringOption(specificKey)
        specific.getOrElse(default)
      case _ => default
    }
  }

  private def _activity(config: AppConfig, key: String, behavior: Behavior): Set[ActiveWindowRules] = {
    if (config.getConfigBooleanReq(s"$key.enabled")) {
      val windows = config.getConfigListOfStringReq(s"$key.time-windows")
        .map(x => ActiveWindowRules(VariableDuration(x), behavior))

      val monthly =
        if (config.getConfigBooleanReq(s"$key.monthly-window")) Seq(ActiveWindowRules(CalendarMonth, behavior))
        else Set.empty

      (windows ++ monthly).toSet
    } else Set.empty
  }

  def findActivityWindow(config: AppConfig): ActivityWindow = {
    val au = _activity(config, ACTIVE_USER_METRIC, ActiveUsers)
    val ar = _activity(config, ACTIVE_RELATIONSHIP_METRIC, ActiveRelationships)

    ActivityWindow(au ++ ar)
  }

  def sponsorMetricTagEnabled(config: AppConfig): Boolean =
    config.getConfigBooleanReq(PROTOCOL_TAG_USES_SPONSOR)

  def sponseeMetricTagEnabled(config: AppConfig): Boolean =
    config.getConfigBooleanReq(PROTOCOL_TAG_USES_SPONSEE)

  def getSponsorRelTag(config: AppConfig, sponsorRel: SponsorRel): Map[String, String] = {
    var a: Map[String, String] = Map()
    if(sponsorMetricTagEnabled(config)) a = a ++ Map("sponsorId" -> sponsorRel.sponsorId)
    if(sponseeMetricTagEnabled(config)) a = a ++ Map("sponseeId" -> sponsorRel.sponseeId)
    a
  }


  private def emptyToUndefined(x: String): String = if (x.trim.nonEmpty) x else "undefined"

  private def validateRetentionPolicy(policy: String): String =
    Try(Duration(policy)) match {
      case Success(p) if p.toDays <= MAX_RETENTION_POLICY  =>
        policy
      case Success(p) if p.toDays > MAX_RETENTION_POLICY =>
        throw new ConfigException.BadValue(
          policy, s"Data Retention Policy must be less than $MAX_RETENTION_POLICY, found policy: $policy"
        )
      case Failure(e) =>
        throw new ConfigException.BadValue(policy, s"Couldn't parse $policy with exception: $e")
    }

  def dataRetentionTag(x: String): String = s"${Duration(x).toDays}d"

  def getDataRetentionPolicy(config: AppConfig, domainId: String, protoref: String): String = {
    val basePath = "verity.retention-policy"
    val domainConfig = config.getConfigOption(s"$basePath.${emptyToUndefined(domainId)}")
    val defaultConfig = config.config.getConfig(s"$basePath.default")

    val policy = domainConfig match {
      case Some(x) => getPolicy(x, protoref)
      case None => getPolicy(defaultConfig, protoref)
    }

    config.logger.debug(s"data retention policy: $policy found for protocol: $protoref - domain: $domainId")
    dataRetentionTag(validateRetentionPolicy(policy))
  }

  private def getPolicy(config: Config, protoref: String): String = {
    AppConfigWrapper.getConfigStringOption(config, emptyToUndefined(protoref)) match {
      case Some(x) => x
      case None => AppConfigWrapper
        .getConfigStringOption(config, "undefined-fallback")
        .getOrElse(throw new ConfigException.Missing("Must define Data Retention Policy 'undefined-fallback'"))
    }
  }


  /**
   * reads 'receive timeout' configuration
   *
   * @param appConfig
   * @param defaultReceiveTimeoutInSeconds
   * @param entityCategory
   * @param entityType
   * @param entityId
   * @return receive timeout
   */
  def getReceiveTimeout(appConfig: AppConfig,
                        defaultReceiveTimeoutInSeconds: Int,
                        entityCategory: String,
                        entityType: String,
                        entityId: String): Duration = {
    val confValue = getConfIntValue(appConfig, entityCategory, RECEIVE_TIMEOUT_SECONDS, Option(entityType), Option(entityId))
    val timeout = confValue.getOrElse(defaultReceiveTimeoutInSeconds)
    if (timeout > 0) timeout.seconds else Duration.Undefined
  }

  def getConfIntValue(appConfig: AppConfig,
                      entityCategory: String,
                      confName: String,
                      entityTypeOpt: Option[String],
                      entityIdOpt: Option[String]): Option[Int] = {
    getConfValue(appConfig, entityCategory, confName, entityTypeOpt, entityIdOpt).map(_.toInt)
  }

  def getConfDoubleValue(appConfig: AppConfig,
                         entityCategory: String,
                         confName: String,
                         entityTypeOpt: Option[String],
                         entityIdOpt: Option[String]): Option[Double] = {
    getConfValue(appConfig, entityCategory, confName, entityTypeOpt, entityIdOpt).map(_.toDouble)
  }

  def getConfBooleanValue(appConfig: AppConfig,
                          entityCategory: String,
                          confName: String,
                          entityTypeOpt: Option[String],
                          entityIdOpt: Option[String]): Option[Boolean] = {
    val normalizedEntityTypeOpt = normalizedEntityTypeName(entityTypeOpt)
    val entityIdConfValue: Option[Boolean] =
      (entityTypeOpt, entityIdOpt) match {
        case (Some(entityType), Some(entityId)) =>
          safeGetAppConfigBooleanOption(s"$entityCategory.$entityType.$entityId.$confName", appConfig)
        case _ => None
      }
    val entityTypeConfValue: Option[Boolean] =
      normalizedEntityTypeOpt match {
        case Some(entityType) => safeGetAppConfigBooleanOption(s"$entityCategory.$entityType.$confName", appConfig)
        case _ => None
      }
    val categoryConfValue: Option[Boolean] =
      safeGetAppConfigBooleanOption(s"$entityCategory.$confName", appConfig)

    entityIdConfValue orElse entityTypeConfValue orElse categoryConfValue
  }

  def getConfStringValue(appConfig: AppConfig,
                         entityCategory: String,
                         confName: String,
                         entityTypeOpt: Option[String],
                         entityIdOpt: Option[String]): Option[String] = {
    getConfValue(appConfig, entityCategory, confName, entityTypeOpt, entityIdOpt)
  }

  private def getConfValue(appConfig: AppConfig,
                           entityCategory: String,
                           confName: String,
                           entityTypeOpt: Option[String],
                           entityIdOpt: Option[String]): Option[String] = {
    val normalizedEntityTypeOpt = normalizedEntityTypeName(entityTypeOpt)
    val entityIdConfValue: Option[String] =
      (entityTypeOpt, entityIdOpt) match {
        case (Some(entityType), Some(entityId)) =>
          safeGetAppConfigStringOption(s"$entityCategory.$entityType.$entityId.$confName", appConfig)
        case _ => None
      }
    val entityTypeConfValue: Option[String] =
      normalizedEntityTypeOpt match {
        case Some(entityType) => safeGetAppConfigStringOption(s"$entityCategory.$entityType.$confName", appConfig)
        case _ => None
      }
    val categoryConfValue: Option[String] =
      safeGetAppConfigStringOption(s"$entityCategory.$confName", appConfig)

    entityIdConfValue orElse entityTypeConfValue orElse categoryConfValue
  }

  private def safeGetAppConfigStringOption(key: String, appConfig: AppConfig): Option[String] =
    try {
      appConfig.getConfigStringOption(safeKey(key))
    } catch {
      case e: ConfigException =>
        logger.warn(s"exception during getting key: $key from config: $e")
        None
    }


  private def safeGetAppConfigBooleanOption(key: String, appConfig: AppConfig): Option[Boolean] =
    try {
      appConfig.getConfigBooleanOption(safeKey(key))
    } catch {
      case e: ConfigException =>
        logger.warn(s"exception during getting key: $key from config: $e")
        None
    }

  private def safeKey(key: String): String = {
    val updateKey =
      key
      .replace("[", "\"[\"")
        .replace("]", "\"]\"")
    s"""$updateKey"""
  }

  /**
   * There are some legacy agent region actor names used for previously created actors
   * but under the hood they use the same actor code and config.
   * So this function normalized those legacy actor names to current ones
   * and that way they will be able to use proper configuration for the entity type
   * @param entityTypeOpt
   * @return
   */
  def normalizedEntityTypeName(entityTypeOpt: Option[String]): Option[String] = entityTypeOpt match {
    case Some("ConsumerAgent"|"EnterpriseAgent"|"VerityAgent")
        => Some(USER_AGENT_REGION_ACTOR_NAME)
    case Some("ConsumerAgentPairwise"|"EnterpriseAgentPairwise"|"VerityAgentPairwise")
        => Some(USER_AGENT_PAIRWISE_REGION_ACTOR_NAME)
    case other => other
  }
}
