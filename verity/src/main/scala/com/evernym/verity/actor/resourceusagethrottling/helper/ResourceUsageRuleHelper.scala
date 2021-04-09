package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.constants.Constants._
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

  val DEFAULT_USAGE_RULE_NAME = "default"

  var resourceUsageRules: ResourceUsageRuleConfig = _

  def loadResourceUsageRules(): Unit = {
    resourceUsageRules = new ResourceUsageRuleConfigValidator(AppConfigWrapper.getLoadedConfig).buildResourceUsageRules()
  }

  def isIpAddressInTokenSet(ipAddress: IpAddress, tokens: Set[ApiToken]): Boolean = {
    tokens.filter(SubnetUtilsExt.isSupportedIPAddress).exists(SubnetUtilsExt.getSubnetUtilsExt(_).getSubnetInfo.isInRange(ipAddress))
  }

  val OWNER_ID_PATTERN: String = OWNER_ID_PREFIX + "*"
  val COUNTERPARTY_ID_PATTERN: String = COUNTERPARTY_ID_PREFIX + "*"

  def isUserIdInTokenSet(userId: UserId, tokens: Set[ApiToken]): Boolean = {
    tokens.exists {
      case `userId` => true
      case OWNER_ID_PATTERN => userId.startsWith(OWNER_ID_PREFIX)
      case COUNTERPARTY_ID_PATTERN => userId.startsWith(COUNTERPARTY_ID_PREFIX)
      case _ => false
    }
  }

  def getRuleNameByToken(token: ApiToken): UsageRuleName = {
    resourceUsageRules.rulesToTokens.find {
      case (_ , v) =>
        if (SubnetUtilsExt.isClassfulIpAddress(token))
          isIpAddressInTokenSet(token, v)
        else if (isUserIdForResourceUsageTracking(token))
          isUserIdInTokenSet(token, v)
        else
          v.contains(token)
    }.map(_._1).getOrElse(DEFAULT_USAGE_RULE_NAME)
  }

  private def getUsageRuleByToken(token: ApiToken): Option[UsageRule] = {
    resourceUsageRules.usageRules.get(getRuleNameByToken(token))
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

  def getResourceUsageRule(token: ApiToken, resourceType: ResourceType, resourceName: ResourceName): Option[ResourceUsageRule] = {
    getUsageRuleByToken(token).flatMap { ur =>
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
  def isWhitelisted(ipAddress: IpAddress, entityId: EntityId): Boolean = {
    val tokenToBeChecked = Set(ipAddress, entityId)
    tokenToBeChecked.exists(isTokenPresent(_, whitelistedTokens))
  }

  /**
   *
   * @param ipAddress a source IP address
   * @param entityId entity id being tracked
   * @return
   */
  def isBlacklisted(ipAddress: IpAddress, entityId: EntityId): Boolean = {
    val tokenToBeChecked = Set(ipAddress, entityId)
    tokenToBeChecked.exists(isTokenPresent(_, blacklistedTokens))
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