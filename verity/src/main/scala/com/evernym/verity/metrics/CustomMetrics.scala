package com.evernym.verity.metrics

object CustomMetrics {

  private final val AS = "as"   //agent service?
  private final val AGENT_SERVICE = s"$AS.service"

  private final val AS_ENDPOINT_HTTP_AGENT_MSG = s"$AS.endpoint.http.agent.msg"

  private final val AS_SERVICE_LIBINDY_WALLET = s"$AGENT_SERVICE.libindy.wallet"
  private final val AS_SERVICE_DYNAMODB = s"$AGENT_SERVICE.dynamodb"
  private final val AS_SERVICE_FIREBASE = s"$AGENT_SERVICE.firebase"
  private final val AS_SERVICE_BANDWIDTH = s"$AGENT_SERVICE.bandwidth"
  private final val AS_SERVICE_TWILIO = s"$AGENT_SERVICE.twilio"
  private final val AS_SERVICE_OPEN_MARKET = s"$AGENT_SERVICE.open-market"

  final val AS_ENDPOINT_HTTP_AGENT_MSG_COUNT = s"$AS_ENDPOINT_HTTP_AGENT_MSG.count"
  final val AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT = s"$AS_ENDPOINT_HTTP_AGENT_MSG.succeed.count"
  final val AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT = s"$AS_ENDPOINT_HTTP_AGENT_MSG.failed.count"

  final val AS_SERVICE_TWILIO_DURATION = s"$AS_SERVICE_TWILIO.duration"
  final val AS_SERVICE_TWILIO_SUCCEED_COUNT = s"$AS_SERVICE_TWILIO.succeed.count"
  final val AS_SERVICE_TWILIO_FAILED_COUNT = s"$AS_SERVICE_TWILIO.failed.count"
  final val AS_SERVICE_BANDWIDTH_DURATION = s"$AS_SERVICE_BANDWIDTH.duration"
  final val AS_SERVICE_BANDWIDTH_SUCCEED_COUNT = s"$AS_SERVICE_BANDWIDTH.succeed.count"
  final val AS_SERVICE_BANDWIDTH_FAILED_COUNT = s"$AS_SERVICE_BANDWIDTH.failed.count"
  final val AS_SERVICE_OPEN_MARKET_DURATION = s"$AS_SERVICE_OPEN_MARKET.duration"
  final val AS_SERVICE_OPEN_MARKET_SUCCEED_COUNT = s"$AS_SERVICE_OPEN_MARKET.succeed.count"
  final val AS_SERVICE_OPEN_MARKET_FAILED_COUNT = s"$AS_SERVICE_OPEN_MARKET.failed.count"
  final val AS_SERVICE_FIREBASE_DURATION = s"$AS_SERVICE_FIREBASE.duration"
  final val AS_SERVICE_FIREBASE_SUCCEED_COUNT = s"$AS_SERVICE_FIREBASE.succeed.count"
  final val AS_SERVICE_FIREBASE_FAILED_COUNT = s"$AS_SERVICE_FIREBASE.failed.count"
  final val AS_SERVICE_DYNAMODB_PERSIST_SUCCEED_COUNT = s"$AS_SERVICE_DYNAMODB.persist.succeed.count"
  final val AS_SERVICE_DYNAMODB_PERSIST_FAILED_COUNT = s"$AS_SERVICE_DYNAMODB.persist.failed.count"
  final val AS_SERVICE_DYNAMODB_PERSIST_DURATION = s"$AS_SERVICE_DYNAMODB.persist.duration"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_ATTEMPT_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.attempt.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.succeed.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.failed.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_MAX_SIZE_EXCEEDED_CURRENT_COUNT =
    s"$AS_SERVICE_DYNAMODB.snapshot.max-size-exceeded.current.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_THREAD_CONTEXT_SIZE_EXCEEDED_CURRENT_COUNT =
    s"$AS_SERVICE_DYNAMODB.snapshot.thread-context-size-exceeded.current.count"

  final val AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_ATTEMPT_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.delete.attempt.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_SUCCEED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.delete.succeed.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_FAILED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.delete.failed.count"

  final val AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT = s"$AS_SERVICE_DYNAMODB.message.delete.attempt.count"
  final val AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT = s"$AS_SERVICE_DYNAMODB.message.delete.succeed.count"
  final val AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT = s"$AS_SERVICE_DYNAMODB.message.delete.failed.count"

  final val AS_SERVICE_LIBINDY_WALLET_DURATION = s"$AS_SERVICE_LIBINDY_WALLET.duration"
  final val AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT = s"$AS_SERVICE_LIBINDY_WALLET.succeed.count"
  final val AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT = s"$AS_SERVICE_LIBINDY_WALLET.failed.count"

  /**
   * how many times wallet open failed
   */
  final val AS_SERVICE_LIBINDY_WALLET_OPEN_ATTEMPT_FAILED_COUNT = s"$AS_SERVICE_LIBINDY_WALLET.open.failed.attempt.count"

  /**
   * how many times wallet open failed even after all retries
   */
  final val AS_SERVICE_LIBINDY_WALLET_OPEN_FAILED_AFTER_ALL_RETRIES_COUNT = s"$AS_SERVICE_LIBINDY_WALLET.open.failed.after.all.retries.count"

  private final val AS_AKKA_ACTOR = s"$AS.akka.actor"
  private final val AS_AKKA_ACTOR_AGENT = s"$AS_AKKA_ACTOR.agent"

  final val AS_ACTOR_AGENT_STATE_SIZE = s"$AS_AKKA_ACTOR_AGENT.state.size"

  private final val AS_USER_AGENT = s"$AS_AKKA_ACTOR.user.agent"
  private final val AS_USER_AGENT_PAIRWISE = s"$AS_AKKA_ACTOR.user.agent.pairwise"

  final val AS_USER_AGENT_PAIRWISE_WATCHER_ACTIVE_CONTAINER_COUNT = s"$AS_USER_AGENT_PAIRWISE.watcher.container.active.count"
  final val AS_USER_AGENT_PAIRWISE_WATCHER_TOTAL_CONTAINER_COUNT = s"$AS_USER_AGENT_PAIRWISE.watcher.container.total.count"

  final val AS_USER_AGENT_API = s"$AS_USER_AGENT.api"

  //this is to track how many pairwise connections are supplied
  // during GET_MSGS_BY_CONNS api call
  final val AS_USER_AGENT_API_GET_MSGS_BY_CONNS_PCS_COUNT =
    s"$AS_USER_AGENT_API.GET_MSGS_BY_CONNS.pairwise.connection.size.count"

  private final val AS_USER_AGENT_MSG = s"$AS_USER_AGENT.msg"
  /**
   * this is overall undelivered messages (including past and current)
   */
  final val AS_USER_AGENT_MSG_UNDELIVERED_COUNT = s"$AS_USER_AGENT_MSG.undelivered.count"

  /**
   * this is total failed messages counts since agent service started
   */
  final val AS_USER_AGENT_MSG_FAILED_COUNT = s"$AS_USER_AGENT_MSG.failed.count"

  /**
   * this is total failed attempts counts since agent service started
   */
  final val AS_USER_AGENT_MSG_FAILED_ATTEMPT_COUNT = s"$AS_USER_AGENT_MSG.failed.attempt.count"


  private final val AS_COLLECTIONS = s"$AS.collections"

  final val AS_COLLECTIONS_MAX = s"$AS_COLLECTIONS.max"
  final val AS_COLLECTIONS_SUM = s"$AS_COLLECTIONS.sum"
  final val AS_COLLECTIONS_COUNT = s"$AS_COLLECTIONS.count"


  final val AS_AKKA_ACTOR_AGENT_RETAINED_MSGS = s"$AS_AKKA_ACTOR_AGENT.msgs.retained"
  final val AS_AKKA_ACTOR_AGENT_REMOVED_MSGS = s"$AS_AKKA_ACTOR_AGENT.msgs.removed"
  final val AS_AKKA_ACTOR_AGENT_WITH_MSGS_REMOVED = s"$AS_AKKA_ACTOR_AGENT.with-msgs-removed"
  /**
   * this is the total number of active users (AU)
   */
  final val AS_NEW_USER_AGENT_COUNT = s"$AS_USER_AGENT.new.users.count"

  /**
   * this is the total number of active users (AU)
   */
  final val AS_ACTIVE_USER_AGENT_COUNT = s"$AS_USER_AGENT.active.users.count"

  /**
   * this is the total number of active relationships for a user agent (AR)
   */
  final val AS_USER_AGENT_ACTIVE_RELATIONSHIPS = s"$AS_USER_AGENT.active.relationships.count"

  private final val AS_PROTOCOL = s"$AS_AKKA_ACTOR.protocol"

  final val AS_NEW_PROTOCOL_COUNT = s"$AS_PROTOCOL.count"

  final val AS_AKKA_ACTOR_TYPE_PREFIX = s"$AS_AKKA_ACTOR.type"
  final val AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX = "started.count"
  final val AS_AKKA_ACTOR_RESTARTED_COUNT_SUFFIX = "restarted.count"
  final val AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX = "stopped.count"

  final val AS_START_TIME = s"$AS.start-time-in-millis"

  final val AS_CACHE = s"$AS.cache"

  final val AS_CACHE_TOTAL_SIZE = s"$AS_CACHE.total.size"
  final val AS_CACHE_SIZE = s"$AS_CACHE.size"
  final val AS_CACHE_HIT_COUNT = s"$AS_CACHE.hit.count"
  final val AS_CACHE_MISS_COUNT = s"$AS_CACHE.miss.count"

  final val TAG_KEY_PATH = "path"
  final val TAG_KEY_TYPE = "type"
  final val TAG_KEY_ID = "id"

  //**NOTE**: We should not add any metrics in this below collection in general,
  // there should be very specific reason to add it to initialize it with value 0
  def initGaugeMetrics(): Unit = {
    val metricsToBeInitialized = Set(
      AS_ACTIVE_USER_AGENT_COUNT,
      AS_USER_AGENT_ACTIVE_RELATIONSHIPS,
      AS_NEW_PROTOCOL_COUNT
    )
    metricsToBeInitialized.foreach(MetricsWriter.gaugeApi.updateWithTags(_, value = 0))
  }
}
