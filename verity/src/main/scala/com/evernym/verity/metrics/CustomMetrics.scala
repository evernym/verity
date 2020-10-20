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
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.succeed.count"
  final val AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT = s"$AS_SERVICE_DYNAMODB.snapshot.failed.count"
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

  final val AS_ACTOR_AGENT_STATE_SIZE = s"$AS_AKKA_ACTOR.agent.state.size"

  private final val AS_USER_AGENT = s"$AS_AKKA_ACTOR.user.agent"
  private final val AS_USER_AGENT_PAIRWISE = s"$AS_AKKA_ACTOR.user.agent.pairwise"

  final val AS_USER_AGENT_PAIRWISE_WATCHER_ACTIVE_CONTAINER_COUNT = s"$AS_USER_AGENT_PAIRWISE.watcher.container.active.count"
  final val AS_USER_AGENT_PAIRWISE_WATCHER_TOTAL_CONTAINER_COUNT = s"$AS_USER_AGENT_PAIRWISE.watcher.container.total.count"

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

  final val AS_AKKA_ACTOR_TYPE_PREFIX = s"$AS_AKKA_ACTOR.type"
  final val AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX = s"started.count"
  final val AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX = s"stopped.count"

  final val AS_START_TIME = s"$AS.start-time-in-millis"

  final val TAG_KEY_PATH = "path"
  final val TAG_KEY_TYPE = "type"
  final val TAG_KEY_ID = "id"

  def initGaugeMetrics(): Unit = {
    val metricsToBeInitialized = Set(
      AS_ENDPOINT_HTTP_AGENT_MSG_COUNT,
      AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT,
      AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT,
      AS_SERVICE_TWILIO_DURATION,
      AS_SERVICE_TWILIO_SUCCEED_COUNT,
      AS_SERVICE_TWILIO_FAILED_COUNT,
      AS_SERVICE_BANDWIDTH_DURATION,
      AS_SERVICE_BANDWIDTH_SUCCEED_COUNT,
      AS_SERVICE_BANDWIDTH_FAILED_COUNT,
      AS_SERVICE_FIREBASE_DURATION,
      AS_SERVICE_FIREBASE_SUCCEED_COUNT,
      AS_SERVICE_FIREBASE_FAILED_COUNT,
      AS_SERVICE_DYNAMODB_PERSIST_SUCCEED_COUNT,
      AS_SERVICE_DYNAMODB_PERSIST_FAILED_COUNT,
      AS_SERVICE_DYNAMODB_PERSIST_DURATION,
      AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT,
      AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT,
      AS_SERVICE_LIBINDY_WALLET_DURATION,
      AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT,
      AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT,
      AS_USER_AGENT_MSG_UNDELIVERED_COUNT,
      AS_ACTIVE_USER_AGENT_COUNT,
      AS_USER_AGENT_ACTIVE_RELATIONSHIPS,
    )
    metricsToBeInitialized.foreach(MetricsWriter.gaugeApi.updateWithTags(_, value = 0))
  }
}
