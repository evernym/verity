package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelper.isIpAddressInTokenSet
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.isUserId
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

  def isIpAddressInTokenSet(entityId: EntityId, tokens: Set[EntityIdToken]): Boolean = {
    tokens
      .filter(SubnetUtilsExt.isIpAddressOrCidrNotation)
      .exists(SubnetUtilsExt.getSubnetUtilsExt(_).getSubnetInfo.isInRange(entityId))
  }

  private def isUserIdInTokenSet(entityId: EntityId, tokens: Set[EntityIdToken]): Boolean = {
    tokens
      .filterNot(SubnetUtilsExt.isIpAddressOrCidrNotation)
      .exists {
        case `entityId`               => true
        case OWNER_ID_PATTERN         => entityId.startsWith(OWNER_ID_PREFIX)
        case COUNTERPARTY_ID_PATTERN  => entityId.startsWith(COUNTERPARTY_ID_PREFIX)
        case _                        => false
    }
  }

  def getRuleNameByEntityId(entityId: EntityId): UsageRuleName = {
    resourceUsageRules.rulesToTokens.find {
      case (_ , v) =>
        if (SubnetUtilsExt.isClassfulIpAddress(entityId))
          isIpAddressInTokenSet(entityId, v)
        else if (isUserId(entityId))
          isUserIdInTokenSet(entityId, v)
        else
          v.contains(entityId)
    }.map(_._1).getOrElse(getDefaultRuleNameByEntityId(entityId))
  }

  def getDefaultRuleNameByEntityId(entityId: EntityId): String = {
    if (entityId == ENTITY_ID_GLOBAL) GLOBAL_DEFAULT_RULE_NAME
    else if (SubnetUtilsExt.isClassfulIpAddress(entityId)) IP_ADDRESS_DEFAULT_RULE_NAME
    else if (entityId.startsWith(OWNER_ID_PREFIX)) USER_ID_OWNER_DEFAULT_RULE_NAME
    else if (entityId.startsWith(COUNTERPARTY_ID_PREFIX)) USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME
    else DEFAULT_USAGE_RULE_NAME
  }

  private def getUsageRuleByEntityId(entityId: EntityId): Option[UsageRule] = {
    resourceUsageRules.usageRules.get(getRuleNameByEntityId(entityId))
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
    getUsageRuleByEntityId(entityId).flatMap { ur =>
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
                                    rulesToTokens: Map[UsageRuleName, Set[EntityIdToken]],
                                    blacklistedTokens: Set[EntityIdToken],
                                    whitelistedTokens: Set[EntityIdToken],
                                    actionRules: Map[ActionRuleId, ViolationActions]
                                  ){
  val logger: Logger = getLoggerByClass(classOf[ResourceUsageRuleConfig])

  /**
   *
   * @param token token to be checked against whitelisted tokens
   * @return
   */
  def isWhitelisted(token: EntityIdToken): Boolean = {
    isTokenPresent(token, whitelistedTokens)
  }

  /**
   *
   * @param token token to be checked against blacklisted tokens
   * @return
   */
  def isBlacklisted(token: EntityIdToken): Boolean = {
    isTokenPresent(token, blacklistedTokens)
  }

  /**
   *
   * @param token a token associated with request
   * @param tokens configured tokens used for lookup
   * @return
   */
  private def isTokenPresent(token: EntityIdToken, tokens: Set[EntityIdToken]): Boolean = {
    if (SubnetUtilsExt.isClassfulIpAddress(token))
      isIpAddressInTokenSet(token, tokens)
    else
      tokens.contains(token)
  }

}
