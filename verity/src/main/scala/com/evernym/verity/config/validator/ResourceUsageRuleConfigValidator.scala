package com.evernym.verity.config.validator

import com.evernym.verity.Exceptions.ConfigLoadingFailedException
import com.evernym.verity.Status.VALIDATION_FAILED
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{getResourceUniqueName, isUserIdOrPattern}
import com.evernym.verity.actor.resourceusagethrottling.{DEFAULT_USAGE_RULE_NAME, ENTITY_ID_GLOBAL, GLOBAL_DEFAULT_RULE_NAME, IP_ADDRESS_DEFAULT_RULE_NAME, USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME, USER_ID_OWNER_DEFAULT_RULE_NAME}
import com.evernym.verity.actor.resourceusagethrottling.helper.{BucketRule, Instruction, InstructionDetail, ResourceTypeUsageRule, ResourceUsageRule, ResourceUsageRuleConfig, UsageRule, UsageViolationActionExecutorValidator, ViolationActions}
import com.evernym.verity.config.CommonConfig.{BLACKLISTED_TOKENS, RESOURCE_USAGE_RULES, RULE_TO_TOKENS, USAGE_RULES, VIOLATION_ACTION, WHITELISTED_TOKENS}
import com.evernym.verity.config.ConfigUtil.lastKeySegment
import com.evernym.verity.config.validator.base.{ConfigValidator, ConfigValidatorCreator}
import com.evernym.verity.util.SubnetUtilsExt.{getSubnetUtilsExt, isIpAddressOrCidrNotation}
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.{Config, ConfigValue}

import scala.collection.JavaConverters._

//checks resource usage rule configuration
object ResourceUsageRuleConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new ResourceUsageRuleConfigValidator(config)
}

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
    val resourceTypeName = lastKeySegment(key)
    c.getObject(key).asScala.map { case (resourceName, _) =>
      getResourceUniqueName(resourceTypeName, resourceName) ->
        ResourceUsageRule(getResourceUsageBucketRules(c, s"$key.$resourceName"))
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
      tokens.foreach(t => checkIfValidToken(t, s"$RULE_TO_TOKENS.$name"))
    }
    validateRuleToTokenConflicts(RULE_TO_TOKENS, c.rulesToTokens)
  }

  def checkIfValidToken(token: String, confName: String): Unit = {
    val validToken: Boolean = isValidToken(token)
    if (! validToken) {
      val problem = s"invalid token: $token"
      val configOrigin = config.getValue(confName).origin()
      throw getValidationFailedExc(configOrigin, confName, problem)
    }
  }

  def isValidToken(token: String): Boolean = {
    token == ENTITY_ID_GLOBAL ||
      isIpAddressOrCidrNotation(token) ||
      isUserIdOrPattern(token)
  }

  def validateRuleToTokenConflicts(confName: String,
                                   rulesToTokens: Map[String, Set[String]]): Unit = {
    rulesToTokens.foreach { case (rule, tokens) =>
      rulesToTokens.filterNot(_._1 == rule).foreach{ case (otherRule, otherTokens) =>
        validatedIpRangeConflicts(
          TokensToValidate(confName + "." + rule, tokens),
          TokensToValidate(confName + "." + otherRule, otherTokens))
      }
    }
  }

  case class TokensToValidate(confName: String, tokens: Set[String])

  def validatedIpRangeConflicts(tokens: TokensToValidate,
                                otherTokens: TokensToValidate):Unit = {

    val filteredOtherTokens = otherTokens.tokens.filter(isIpAddressOrCidrNotation)
    tokens.tokens.filter(isIpAddressOrCidrNotation).foreach { token =>
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
    val ruleNames = Set(
//      GLOBAL_DEFAULT_RULE_NAME,
//      IP_ADDRESS_DEFAULT_RULE_NAME,
//      USER_ID_OWNER_DEFAULT_RULE_NAME,
//      USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME,
      DEFAULT_USAGE_RULE_NAME
    )
    ruleNames.foreach { rn =>
      if (! c.usageRules.keySet.contains(rn))
        throw new ConfigLoadingFailedException(VALIDATION_FAILED.statusCode,
          Option(s"'$USAGE_RULES' config doesn't contain default configs for '$rn'"))
    }

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
    if (commonTokens.nonEmpty) {
      val problem = s"whitelisted-tokens and blacklisted-tokens config can't contain same tokens: ${commonTokens.mkString(", ")}"
      throw getValidationFailedExc(config.getValue(WHITELISTED_TOKENS).origin(), WHITELISTED_TOKENS, problem)
    }
    c.whitelistedTokens.foreach(t => checkIfValidToken(t, WHITELISTED_TOKENS))
    c.blacklistedTokens.foreach(t => checkIfValidToken(t, BLACKLISTED_TOKENS))
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

      ResourceUsageRuleConfig(applyUsageRules, persistAllUsageStates, snapshotAfterEvents,
        rules, ruleToTokens, blacklisted, whitelisted, actionRules)
    } catch {
      case _: Missing =>
        ResourceUsageRuleConfig(applyUsageRules = false, persistAllBucketUsages = false, 100,
          Map.empty, Map.empty, Set.empty, Set.empty, Map.empty)
    }
    rurc
  }

}
