package com.evernym.verity.actor

package object resourceusagethrottling {

  /**
   * EntityId can be an "ip address" or "user id" or "global"
   * which is being tracked
   */
  type EntityId = String

  /**
   * ip address
   */
  type IpAddress = String


  /**
   * any identifier used to identify a unique user,
   * is always started with "owner-" or "counterparty-" prefix
   */
  type UserId = String

  /**
   * Resource Type identifier (for 'endpoint' it is "1", for 'message' it is "2")
   */
  type ResourceType = Int

  /**
   * human readable resource type name ('endpoint' or 'message')
   */
  type ResourceTypeName = String

  /**
   * Resource Name (endpoint-path or message-name)
   * endpoint-path examples: /agency/msg etc
   * message-name examples: "GET_MSGS" etc
   */
  type ResourceName = String

  /**
   * a token to look into 'rule-to-tokens' in 'resource-usage-rule.conf'
   * to find out which usage rule is applicable
   */
  type ApiToken = String

  /**
   * 'usage-rules' (in resource-usage-rule.conf) allows to define multiple rules
   * each rule is identified with an id called usage rule name
   */
  type UsageRuleName = String

  /**
   * 'violation-action' (in resource-usage-rule.conf) allows to define multiple actions rules
   * each rule is identified with an id called action rule id
   */
  type ActionRuleId = String

  /**
   * 'violation-action'.'<ActionRuleId>' contains multiple instructions
   * each instruction is identified with a name called instruction name
   */
  type InstructionName = String

  /**
   * 'usage-rules' (in resource-usage-rule.conf) at the bottom level contains
   * different buckets (like '300' second bucket, '600' second bucket etc)
   * each bucket is identified with an id called bucket id
   */
  type BucketId = Int

  type BucketIdStr = String

  /**
   * a limit for how many times a resource should be allowed to use without any restrictions
   */
  type UsageLimit = Int

  /**
   * counter for how many times a resource has been used in a given bucket
   */
  type UsedCount = Int

  val DEFAULT_USAGE_RULE_NAME = "default"
  val GLOBAL_DEFAULT_RULE_NAME = "global"
  val IP_ADDRESS_DEFAULT_RULE_NAME = "ip-address"
  val USER_ID_OWNER_DEFAULT_RULE_NAME = "user-id-owner"
  val USER_ID_COUNTERPARTY_DEFAULT_RULE_NAME = "user-id-counterparty"

  val ENTITY_ID_GLOBAL = "global"

  val RESOURCE_NAME_ALL = "all"

  val RESOURCE_TYPE_ENDPOINT = 1
  val RESOURCE_TYPE_MESSAGE = 2

  val RESOURCE_TYPE_NAME_ENDPOINT = "endpoint"
  val RESOURCE_TYPE_NAME_MESSAGE = "message"

  val OWNER_ID_PREFIX = "owner-"
  val COUNTERPARTY_ID_PREFIX = "counterparty-"
}
