package com.evernym.verity.actor.resourceusagethrottling.helper

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.actor.resourceusagethrottling._
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageRuleHelper.isIpAddressInTokenSet
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{getResourceTypeName, isUserId}
import com.evernym.verity.config.validator.ResourceUsageRuleConfigValidator
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util._
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

class ResourceUsageRuleHelper(val config: Config) {

  loadResourceUsageRules(config)

  var resourceUsageRules: ResourceUsageRuleConfig = _

  def loadResourceUsageRules(config: Config): Unit = {
    resourceUsageRules = new ResourceUsageRuleConfigValidator(config).buildResourceUsageRules()
  }

  val OWNER_ID_PATTERN: String = OWNER_ID_PREFIX + "*"
  val COUNTERPARTY_ID_PATTERN: String = COUNTERPARTY_ID_PREFIX + "*"

  private def isUserIdInTokenSet(userId: UserId, tokens: Set[EntityIdToken]): Boolean = {
    tokens
      .filterNot(SubnetUtilsExt.isIpAddressOrCidrNotation)
      .exists {
        case `userId`               => true
        case OWNER_ID_PATTERN         => userId.startsWith(OWNER_ID_PREFIX)
        case COUNTERPARTY_ID_PATTERN  => userId.startsWith(COUNTERPARTY_ID_PREFIX)
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
    }.map(_._1).getOrElse {
      val defaultByEntityId = getDefaultRuleNameByEntityId(entityId)
      if (resourceUsageRules.usageRules.contains(defaultByEntityId)) defaultByEntityId else DEFAULT_USAGE_RULE_NAME
    }
  }

  def getDefaultRuleNameByEntityId(entityId: EntityId): String = {
    if (entityId == ENTITY_ID_GLOBAL) GLOBAL_DEFAULT_RULE_NAME
    else if (SubnetUtilsExt.isClassfulIpAddress(entityId)) IP_ADDRESS_DEFAULT_RULE_NAME
    else if (entityId.startsWith(OWNER_ID_PREFIX)) USER_ID_OWNER_DEFAULT_RULE_NAME
    else if (entityId.startsWith(COUNTERPARTY_ID_PREFIX)) USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME
    else DEFAULT_USAGE_RULE_NAME
  }

  private def getUsageRuleByEntityId(entityId: EntityId): UsageRule = {
    resourceUsageRules.usageRules(getRuleNameByEntityId(entityId))
  }

  private def getResourceTypeUsageRule(ur: UsageRule, resourceTypeName: ResourceTypeName): Option[ResourceTypeUsageRule] = {
    ur.resourceTypeUsageRules.get(resourceTypeName)
  }

  private def getResourceUsageRule(rtur: ResourceTypeUsageRule, resourceName: ResourceName):
  Option[ResourceUsageRule] = {
    val rur = rtur.resourceUsageRules.get(resourceName)
    if (resourceName == RESOURCE_NAME_ENDPOINT_ALL || resourceName == RESOURCE_NAME_MESSAGE_ALL) rur
    else rur orElse rtur.resourceUsageRules.get(DEFAULT_RESOURCE_USAGE_RULE_NAME)
  }

  def getResourceUsageRule(entityId: EntityId,
                           resourceType: ResourceType,
                           resourceName: ResourceName): Option[ResourceUsageRule] = {
    val ur = getUsageRuleByEntityId(entityId)
    val resourceTypeName = getResourceTypeName(resourceType)
    getResourceTypeUsageRule(ur, resourceTypeName).flatMap { rtur =>
      getResourceUsageRule(rtur, resourceName)
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
   * @param ipAddress ip address of the request
   * @param entityId entityId to be checked against whitelisted tokens
   * @return
   */
  def isWhitelisted(ipAddress: IpAddress, entityId: EntityId): Boolean = {
    val tokenToBeChecked = Set(ipAddress, entityId)
    tokenToBeChecked.exists(isTokenPresent(_, whitelistedTokens))
  }

  /**
   *
   * @param ipAddress ip address of the request
   * @param entityId entityId to be checked against blacklisted tokens
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
  private def isTokenPresent(token: EntityIdToken, tokens: Set[EntityIdToken]): Boolean = {
    if (SubnetUtilsExt.isClassfulIpAddress(token))
      isIpAddressInTokenSet(token, tokens)
    else
      tokens.contains(token)
  }

}

class ResourceUsageRuleHelperExtensionImpl(config: Config) extends Extension {
  val resourceUsageRuleHelper: ResourceUsageRuleHelper = new ResourceUsageRuleHelper(config)

  def get(): ResourceUsageRuleHelper = resourceUsageRuleHelper
}

object ResourceUsageRuleHelperExtension extends ExtensionId[ResourceUsageRuleHelperExtensionImpl] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): ResourceUsageRuleHelperExtensionImpl =
    new ResourceUsageRuleHelperExtensionImpl(system.settings.config)

  override def lookup: ExtensionId[_ <: Extension] = ResourceUsageRuleHelperExtension
}

object ResourceUsageRuleHelper {
  def isIpAddressInTokenSet(ipAddress: IpAddress, tokens: Set[EntityIdToken]): Boolean = {
    tokens
      .filter(SubnetUtilsExt.isIpAddressOrCidrNotation)
      .exists(SubnetUtilsExt.getSubnetUtilsExt(_).getSubnetInfo.isInRange(ipAddress))
  }
}