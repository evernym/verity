package com.evernym.verity.constants

object ActorNameConstants {

  //once below mentioned values are in use, then it shouldn't be changed
  //unless you have appropriate db migration ready
  val RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME = "ResourceUsageTracker"
  val CLUSTER_SINGLETON_MANAGER_PROXY = "singleton-parent-proxy"
  val TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME = "TokenToActorItemMapper"
  val URL_STORE_REGION_ACTOR_NAME=  "UrlMapper"
  val AGENT_ROUTE_STORE_REGION_ACTOR_NAME = "RoutingAgent"
  val SEGMENTED_STATE_REGION_ACTOR_NAME = "SegmentedState"
  val SYNC_MSG_RESPONDER_REGION_ACTOR_NAME = "SyncMsgResponder"
  val AGENCY_AGENT_REGION_ACTOR_NAME = "AgencyAgent"
  val AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME = "AgencyAgentPairwise"
  val USER_AGENT_REGION_ACTOR_NAME = "UserAgent"
  val USER_AGENT_PAIRWISE_REGION_ACTOR_NAME = "UserAgentPairwise"
  val WALLET_REGION_ACTOR_NAME = "WalletActor"
  val ACTIVITY_TRACKER_REGION_ACTOR_NAME = "ActivityTracker"
  val ITEM_MANAGER_REGION_ACTOR_NAME = "ItemManager"
  val ITEM_CONTAINER_REGION_ACTOR_NAME = "ItemContainer"
  val MSG_TRACER_REGION_ACTOR_NAME = "MsgTracer"
  val MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME = "MsgProgressTracker"
  val ACTOR_STATE_CLEANUP_EXECUTOR = "ActorStateCleanupExecutor"
  val RESOURCE_USAGE_TRACKER = "resource-usage-tracker"
  val LIBINDY_METRICS_TRACKER = "LibindyMetricsTracker"
  val COLLECTIONS_METRICS_COLLECTOR = "CollectionsMetricsCollector"

  //cluster singleton actors
  val CLUSTER_SINGLETON_MANAGER = "cluster-singleton-mngr"

  val CLUSTER_SINGLETON_PARENT = "singleton-parent"
  val KEY_VALUE_MAPPER_ACTOR_NAME = "key-value-mapper"
  val RESOURCE_BLOCKING_STATUS_MNGR = "user-blocking-status-mngr"
  val RESOURCE_WARNING_STATUS_MNGR = "user-warning-status-mngr"
  val WATCHER_MANAGER = "watcher-manager"
  val ACTOR_STATE_CLEANUP_MANAGER = "actor-state-cleanup-manager"
  val ROUTE_MAINTENANCE_HELPER = "route-maintenance-helper"

  //actor path/name related
  val SHARDED_ACTOR_PATH_PREFIX = "/system/sharding"
  val SINGLETON_PARENT_PROXY = s"/user/$CLUSTER_SINGLETON_MANAGER_PROXY"
  val NODE_SINGLETON_PATH = "/user/node-singleton"

  //actor type related
  val ACTOR_TYPE_AGENCY_AGENT_ACTOR = 1
  val ACTOR_TYPE_AGENCY_AGENT_PAIRWISE_ACTOR = 2

  val LEGACY_ACTOR_TYPE_USER_AGENT_ACTOR = 3
  val LEGACY_ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR = 4

  val ACTOR_TYPE_USER_AGENT_ACTOR = 5
  val ACTOR_TYPE_USER_AGENT_PAIRWISE_ACTOR = 6

  val DEFAULT_ENTITY_TYPE = "Default"
}
