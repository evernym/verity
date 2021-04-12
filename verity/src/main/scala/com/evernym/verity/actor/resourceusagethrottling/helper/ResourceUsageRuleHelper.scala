package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelper.isIpAddressInTokenSet
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.isUserIdForResourceUsageTracking
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.validator.ResourceUsageRuleConfigValidator
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util._
import com.typesafe.scalalogging.Logger

object ResourceUsageRuleHelper {

  loadResourceUsageRules()

  var resourceUsageRules: ResourceUsageRuleConfig = _

  def loadResourceUsageRules(): Unit = {
    resourceUsageRules = new ResourceUsageRuleConfigValidator(AppConfigWrapper.getLoadedConfig).buildResourceUsageRules()
  }

  val OWNER_ID_PATTERN: String = OWNER_ID_PREFIX + "*"
  val COUNTERPARTY_ID_PATTERN: String = COUNTERPARTY_ID_PREFIX + "*"

  def isIpAddressInTokenSet(entityId: EntityId, tokens: Set[ApiToken]): Boolean = {
    tokens
      .filter(SubnetUtilsExt.isSupportedIPAddress)
      .exists(SubnetUtilsExt.getSubnetUtilsExt(_).getSubnetInfo.isInRange(entityId))
  }

  private def isUserIdInTokenSet(entityId: EntityId, tokens: Set[ApiToken]): Boolean = {
    tokens
      .filterNot(SubnetUtilsExt.isSupportedIPAddress)
      .exists {
        case `entityId`               => true
        case OWNER_ID_PATTERN         => entityId.startsWith(OWNER_ID_PREFIX)
        case COUNTERPARTY_ID_PATTERN  => entityId.startsWith(COUNTERPARTY_ID_PREFIX)
        case _                        => false
    }
  }

  def getRuleNameByTrackingEntityId(entityId: EntityId): UsageRuleName = {
    resourceUsageRules.rulesToTokens.find {
      case (_ , v) =>
        if (SubnetUtilsExt.isClassfulIpAddress(entityId))
          isIpAddressInTokenSet(entityId, v)
        else if (isUserIdForResourceUsageTracking(entityId))
          isUserIdInTokenSet(entityId, v)
        else
          v.contains(entityId)
    }.map(_._1).getOrElse(getDefaultRuleNameByEntityId(entityId))
  }

  def getDefaultRuleNameByEntityId(entityId: EntityId): String = {
    if (entityId == ENTITY_ID_GLOBAL) GLOBAL_DEFAULT_RULE_NAME
    else if (SubnetUtilsExt.isSupportedIPAddress(entityId)) IP_ADDRESS_DEFAULT_RULE_NAME
    else if (entityId.startsWith(OWNER_ID_PREFIX)) USER_ID_OWNER_DEFAULT_RULE_NAME
    else if (entityId.startsWith(COUNTERPARTY_ID_PREFIX)) USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME
    else DEFAULT_USAGE_RULE_NAME
  }

  private def getUsageRuleByTrackingEntityId(entityId: EntityId): Option[UsageRule] = {
    resourceUsageRules.usageRules.get(getRuleNameByTrackingEntityId(entityId))
  }

  private def getResourceTypeUsageRule(ur: UsageRule, resourceTypeName: ResourceTypeName): Option[ResourceTypeUsageRule] = {
    ur.resourceTypeUsageRules.get(resourceTypeName)
  }

  private def getResourceUsageRule(rtur: ResourceTypeUsageRule, resourceName: ResourceName):
  Option[ResourceUsageRule] = {
    val rur = rtur.resourceUsageRules.get(resourceName)
    if (resourceName == RESOURCE_NAME_ALL) rur
    else rur orElse rtur.resourceUsageRules.get(DEFAULT_USAGE_RULE_NAME)
  }

  def getHumanReadableResourceType(resourceType: ResourceType): ResourceTypeName = {
    resourceType match {
      case 1 => RESOURCE_TYPE_NAME_ENDPOINT
      case 2 => RESOURCE_TYPE_NAME_MESSAGE
      case _ => "unknown"
    }
  }

  def getResourceUsageRule(entityId: EntityId,
                           resourceType: ResourceType,
                           resourceName: ResourceName): Option[ResourceUsageRule] = {
    getUsageRuleByTrackingEntityId(entityId).flatMap { ur =>
      val resourceTypeName = getHumanReadableResourceType(resourceType)
      getResourceTypeUsageRule(ur, resourceTypeName).flatMap { rtur =>
        getResourceUsageRule(rtur, resourceName)
      }
    }
  }

}


case class InstructionDetail(detail: Map[String, Any])

case class ViolationActions(instructions: Map[InstructionName, InstructionDetail])

case class BucketRule(allowedCount: Int, violationActionId: ActionRuleId, persistUsageState: Boolean=false)

case class ResourceUsageRule(bucketRules: Map[BucketId, BucketRule])

case class ResourceTypeUsageRule(resourceUsageRules: Map[ResourceName, ResourceUsageRule])

case class UsageRule(resourceTypeUsageRules: Map[ResourceTypeName, ResourceTypeUsageRule])

case class ResourceUsageRuleConfig(
                                    applyUsageRules: Boolean,
                                    persistAllBucketUsages: Boolean,
                                    snapshotAfterEvents: Int,
                                    usageRules: Map[UsageRuleName, UsageRule],
                                    rulesToTokens: Map[UsageRuleName, Set[ApiToken]],
                                    blacklistedTokens: Set[ApiToken],
                                    whitelistedTokens: Set[ApiToken],
                                    actionRules: Map[ActionRuleId, ViolationActions],
                                    tokenCharsetRegex: Option[String] = None,
                                    ipCheckRegex: Option[String] = None
                                  ){
  val logger: Logger = getLoggerByClass(classOf[ResourceUsageRuleConfig])

  /**
   *
   * @param ipAddress a source IP address
   * @param entityId entity id being tracked
   * @return
   */
  def isWhitelisted(tokens: ApiToken*): Boolean = {
    tokens.exists(isTokenPresent(_, whitelistedTokens))
  }

  /**
   *
   * @param tokens tokens to be checked against blacklisted tokens
   * @return
   */
  def isBlacklisted(tokens: ApiToken*): Boolean = {
    tokens.exists(isTokenPresent(_, blacklistedTokens))
  }

  /**
   *
   * @param token a token associated with request
   * @param tokens configured tokens used for lookup
   * @return
   */
  private def isTokenPresent(token: ApiToken, tokens: Set[ApiToken]): Boolean = {
    if (SubnetUtilsExt.isClassfulIpAddress(token))
      isIpAddressInTokenSet(token, tokens)
    else
      tokens.contains(token)
  }

}