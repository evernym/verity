package com.evernym.verity.config


trait CommonConfig {

  val VERITY = "verity"

  val VERITY_THREAD_POOLS = s"$VERITY.thread-pools"
  val VERITY_DEFAULT_FUTURE_THREAD_POOL_SIZE = s"$VERITY_THREAD_POOLS.default-future.size"
  val VERITY_WALLET_FUTURE_THREAD_POOL_SIZE = s"$VERITY_THREAD_POOLS.wallet-future.size"

  val VERITY_DOMAIN_URL_PREFIX = s"$VERITY.domain-url-prefix"
  val VERITY_ENDPOINT = s"$VERITY.endpoint"
  val VERITY_ENDPOINT_HOST = s"$VERITY_ENDPOINT.host"
  val VERITY_ENDPOINT_PORT = s"$VERITY_ENDPOINT.port"
  val VERITY_ENDPOINT_PATH_PREFIX = s"$VERITY_ENDPOINT.path-prefix"

  private val HTTP = s"$VERITY.http"
  val HTTP_INTERFACE = s"$HTTP.interface"
  val HTTP_PORT = s"$HTTP.port"
  val HTTP_SSL_PORT = s"$HTTP.ssl-port"

  private val KEYSTORE = s"$VERITY.keystore"
  val KEYSTORE_LOCATION = s"$KEYSTORE.location"
  val KEYSTORE_PASSWORD = s"$KEYSTORE.password"

  private val SERVICES = s"$VERITY.services"

  val PUSH_NOTIF = s"$SERVICES.push-notif-service"
  val PUSH_NOTIF_ENABLED = s"$PUSH_NOTIF.enabled"
  private val FCM = s"$PUSH_NOTIF.fcm"
  val FCM_API_HOST = s"$FCM.host"
  val FCM_API_PATH = s"$FCM.path"
  val FCM_API_KEY = s"$FCM.key"

  val MCM_ENABLED = s"$PUSH_NOTIF.mcm.enabled"
  val MCM_SEND_MSG = s"$PUSH_NOTIF.mcm.send-messages-to-endpoint"

  val PUSH_NOTIF_GENERAL_MSG_TITLE_TEMPLATE = s"$PUSH_NOTIF.general-msg-title-template"
  val PUSH_NOTIF_GENERAL_NEW_MSG_BODY_TEMPLATE = s"$PUSH_NOTIF.general-new-msg-body-template"
  val PUSH_NOTIF_DEFAULT_LOGO_URL = s"$PUSH_NOTIF.default-logo-url"
  val PUSH_NOTIF_DEFAULT_SENDER_NAME = s"$PUSH_NOTIF.default-sender-name"
  val PUSH_NOTIF_ERROR_RESP_MSG_BODY_TEMPLATE = s"$PUSH_NOTIF.error-resp-msg-body-template"
  val PUSH_NOTIF_SUCCESS_RESP_MSG_BODY_TEMPLATE = s"$PUSH_NOTIF.success-resp-msg-body-template"
  val PUSH_NOTIF_MSG_TYPES_FOR_ALERT_PUSH_MSGS = s"$PUSH_NOTIF.msg-types-for-alert-push-notif"
  val PUSH_NOTIF_INVALID_TOKEN_ERROR_CODES = s"$PUSH_NOTIF.invalid-token-error-codes"
  val PUSH_NOTIF_WARN_ON_ERROR_LIST = s"$PUSH_NOTIF.warn-on-error-list"

  private val VAULT = s"$SERVICES.vault"
  val VAULT_HOST = s"$VAULT.host"
  val VAULT_PORT = s"$VAULT.port"
  val VAULT_TLS = s"$VAULT.tls"

  val URL_MAPPER_SVC = s"$SERVICES.url-mapper-service"
  val URL_MAPPER_SVC_ENDPOINT = s"$URL_MAPPER_SVC.endpoint"
  val URL_MAPPER_SVC_ENDPOINT_HOST = s"$URL_MAPPER_SVC_ENDPOINT.host"
  val URL_MAPPER_SVC_ENDPOINT_PORT = s"$URL_MAPPER_SVC_ENDPOINT.port"
  val URL_MAPPER_SVC_ENDPOINT_PATH_PREFIX = s"$URL_MAPPER_SVC_ENDPOINT.path-prefix"

  val URL_MAPPER_SERVER_MSG_TEMPLATE = s"$URL_MAPPER_SVC.msg-template"
  val CONNECT_ME_MAPPED_URL_TEMPLATE = s"$URL_MAPPER_SERVER_MSG_TEMPLATE.connect-me-mapped-url-template"

  val URL_SHORTENER_SVC = s"$SERVICES.url-shortener-service"
  val URL_SHORTENER_SVC_SELECTED = s"$URL_SHORTENER_SVC.selected"

  private val YOURLS = s"$URL_SHORTENER_SVC.yourls"
  val YOURLS_API_URL = s"$YOURLS.api-url"
  val YOURLS_API_SIGNATURE = s"$YOURLS.signature"
  val YOURLS_API_USERNAME = s"$YOURLS.username"
  val YOURLS_API_PASSWORD = s"$YOURLS.password"
  val YOURLS_API_TIMEOUT_SECONDS = s"$YOURLS.timeout-seconds"

  private val SMS_SVC = s"$SERVICES.sms-service"
  val SMS_SVC_SEND_VIA_LOCAL_AGENCY = s"$SMS_SVC.send-via-local-agency"
  val SMS_SVC_ENDPOINT = s"$SMS_SVC.endpoint"
  val SMS_SVC_ENDPOINT_HOST = s"$SMS_SVC_ENDPOINT.host"
  val SMS_SVC_ENDPOINT_PORT = s"$SMS_SVC_ENDPOINT.port"
  val SMS_SVC_ENDPOINT_PATH_PREFIX = s"$SMS_SVC_ENDPOINT.path-prefix"
  val SMS_SVC_ALLOWED_CLIENT_IP_ADDRESSES = s"$SMS_SVC.allowed-client-ip-addresses"

  private val SMS_EXTERNAL_SVC = s"$SMS_SVC.external-services"
  val SMS_EXTERNAL_SVC_PREFERRED_ORDER = s"$SMS_EXTERNAL_SVC.preferred-order"

  private val OPEN_MARKET = s"$SMS_EXTERNAL_SVC.open-market"
  val OPEN_MARKET_ENDPOINT = s"$OPEN_MARKET.endpoint"
  val OPEN_MARKET_ENDPOINT_HOST = s"$OPEN_MARKET_ENDPOINT.host"
  val OPEN_MARKET_ENDPOINT_PORT = s"$OPEN_MARKET_ENDPOINT.port"
  val OPEN_MARKET_ENDPOINT_PATH_PREFIX = s"$OPEN_MARKET_ENDPOINT.path-prefix"
  val OPEN_MARKET_USER_NAME = s"$OPEN_MARKET.user-name"
  val OPEN_MARKET_PASSWORD = s"$OPEN_MARKET.password"
  val OPEN_MARKET_SERVICE_ID = s"$OPEN_MARKET.service-id"

  private val BANDWIDTH = s"$SMS_EXTERNAL_SVC.bandwidth"
  val BANDWIDTH_TOKEN = s"$BANDWIDTH.token"
  val BANDWIDTH_SECRET = s"$BANDWIDTH.secret"
  val BANDWIDTH_USER_ID = s"$BANDWIDTH.user-id"
  val BANDWIDTH_ENDPOINT = s"$BANDWIDTH.endpoint"
  val BANDWIDTH_ENDPOINT_HOST = s"$BANDWIDTH_ENDPOINT.host"
  val BANDWIDTH_ENDPOINT_PORT = s"$BANDWIDTH_ENDPOINT.port"
  val BANDWIDTH_ENDPOINT_PATH_PREFIX = s"$BANDWIDTH_ENDPOINT.path-prefix"
  val BANDWIDTH_DEFAULT_NUMBER = s"$BANDWIDTH.default-number"
  val BANDWIDTH_APP_ID = s"$BANDWIDTH.app-id"

  private val TWILIO = s"$SMS_EXTERNAL_SVC.twilio"
  val TWILIO_TOKEN = s"$TWILIO.token"
  val TWILIO_ACCOUNT_SID = s"$TWILIO.account-sid"
  val TWILIO_DEFAULT_NUMBER = s"$TWILIO.default-number"
  val TWILIO_APP_NAME = s"$TWILIO.app-name"

  private val LIB_INDY = s"$VERITY.lib-indy"
  val LIB_INDY_LIBRARY_DIR_LOCATION = s"$LIB_INDY.library-dir-location"
  val LIB_INDY_FLAVOR = s"$LIB_INDY.flavor"

  private val LIB_INDY_WALLET = s"$LIB_INDY.wallet"
  val LIB_INDY_WALLET_TYPE = s"$LIB_INDY_WALLET.type"

  private val LIB_INDY_LEDGER = s"$LIB_INDY.ledger"
  val LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION = s"$LIB_INDY_LEDGER.genesis-txn-file-location"
  val LIB_INDY_LEDGER_POOL_NAME = s"$LIB_INDY_LEDGER.pool-name"
  val LIB_INDY_LEDGER_TXN_PROTOCOL_VERSION = s"$LIB_INDY_LEDGER.txn-protocol-version"
  val LIB_INDY_LEDGER_TAA = s"$LIB_INDY_LEDGER.transaction_author_agreement"
  val LIB_INDY_LEDGER_TAA_AGREEMENTS = s"$LIB_INDY_LEDGER_TAA.agreements"
  val LIB_INDY_LEDGER_TAA_ENABLED = s"$LIB_INDY_LEDGER_TAA.enabled"
  val LIB_INDY_LEDGER_TAA_AUTO_ACCEPT = s"$LIB_INDY_LEDGER_TAA.auto-accept"

  private val LIB_INDY_LEDGER_POOL_CONFIG = s"$LIB_INDY_LEDGER.pool-config"
  val LIB_INDY_LEDGER_POOL_CONFIG_CONN_MANAGER_OPEN_TIMEOUT= s"$LIB_INDY_LEDGER_POOL_CONFIG.connection-manager-open-timeout"
  val LIB_INDY_LEDGER_POOL_CONFIG_TIMEOUT= s"$LIB_INDY_LEDGER_POOL_CONFIG.timeout"
  val LIB_INDY_LEDGER_POOL_CONFIG_EXTENDED_TIMEOUT= s"$LIB_INDY_LEDGER_POOL_CONFIG.extended-timeout"
  val LIB_INDY_LEDGER_POOL_CONFIG_CONN_LIMIT= s"$LIB_INDY_LEDGER_POOL_CONFIG.conn-limit"
  val LIB_INDY_LEDGER_POOL_CONFIG_CONN_ACTIVE_TIMEOUT= s"$LIB_INDY_LEDGER_POOL_CONFIG.conn-active-timeout"

  private val WALLET_STORAGE = s"$VERITY.wallet-storage"
  val WALLET_STORAGE_READ_HOST = s"$WALLET_STORAGE.read-host-ip"
  val WALLET_STORAGE_WRITE_HOST = s"$WALLET_STORAGE.write-host-ip"
  val WALLET_STORAGE_HOST_PORT = s"$WALLET_STORAGE.host-port"
  val WALLET_STORAGE_CRED_USERNAME = s"$WALLET_STORAGE.credentials-username"
  val WALLET_STORAGE_CRED_PASSWORD = s"$WALLET_STORAGE.credentials-password"
  val WALLET_STORAGE_DB_NAME = s"$WALLET_STORAGE.db-name"

  private val SALT = s"$VERITY.salt"
  val SALT_WALLET_NAME = s"$SALT.wallet-name"
  val SALT_WALLET_ENCRYPTION = s"$SALT.wallet-encryption"
  val SALT_EVENT_ENCRYPTION = s"$SALT.event-encryption"

  private val SECRET = s"$VERITY.secret"
  val SECRET_ROUTING_AGENT = s"$SECRET.routing-agent"
  val SECRET_URL_STORE = s"$SECRET.url-mapper-actor"
  val SECRET_KEY_VALUE_MAPPER = s"$SECRET.key-value-mapper-actor"
  val SECRET_TOKEN_TO_ACTOR_ITEM_MAPPER = s"$SECRET.token-to-actor-item-mapper-actor"
  val SECRET_RESOURCE_WARNING_STATUS_MNGR = s"$SECRET.user-warning-status-mngr"
  val SECRET_RESOURCE_BLOCKING_STATUS_MNGR = s"$SECRET.user-blocking-status-mngr"
  val SECRET_RESOURCE_USAGE_TRACKER = s"$SECRET.resource-usage-tracker"

  private val MSG_TEMPLATE = s"$VERITY.msg-template"
  val SMS_MSG_TEMPLATE_INVITE_URL = s"$MSG_TEMPLATE.sms-msg-template-invite-url"
  val SMS_MSG_TEMPLATE_OFFER_CONN_MSG = s"$MSG_TEMPLATE.sms-msg-template-offer-conn-msg"
  val SMS_OFFER_TEMPLATE_DEEPLINK_URL = s"$MSG_TEMPLATE.sms-offer-template-deeplink-url"

  val MESSAGES = s"$VERITY.msgs"
  val CONN_REQ_MSG_EXPIRATION_TIME_IN_SECONDS = s"$MESSAGES.conn-req-expiration-time-in-seconds"

  private val CACHE = s"$VERITY.cache"
  val KEY_VALUE_MAPPER_CACHE = s"$CACHE.key-value-mapper"
  val AGENT_CONFIG_CACHE = s"$CACHE.agent-config"
  val AGENCY_DETAIL_CACHE = s"$CACHE.agency-detail"
  val LEDGER_GET_VER_KEY_CACHE = s"$CACHE.ledger-get-ver-key"
  val LEDGER_GET_ENDPOINT_CACHE = s"$CACHE.ledger-get-endpoint"
  val ROUTING_DETAIL_CACHE = s"$CACHE.routing-detail"
  val WALLET_GET_VER_KEY_CACHE = s"$CACHE.wallet-get-ver-key"
  val LEDGER_GET_SCHEMA_CACHE = s"$CACHE.ledger-get-schema"
  val LEDGER_GET_CRED_DEF_CACHE = s"$CACHE.ledger-get-cred-def"

  val INTERNAL_API_URL_MAPPER_ENABLED = s"$VERITY.url-mapper-api.enabled"

  private val INTERNAL_API = s"$VERITY.internal-api"
  val INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES = s"$INTERNAL_API.allowed-from-ip-addresses"
  val INTERNAL_API_PERSISTENT_DATA_ENABLED = s"$INTERNAL_API.persistent-data.enabled"

  val PERSISTENCE = s"$VERITY.persistence"
  val PERSISTENCE_USE_ASYNC_MSG_FORWARD = s"$PERSISTENCE.use-async-for-msg-forward-feature"
  val PERSISTENCE_SNAPSHOT_MAX_ITEM_SIZE_IN_BYTES = s"$PERSISTENCE.snapshot.max-item-size-in-bytes"

  private val KAMON = "kamon"
  val KAMON_ENV = s"$KAMON.environment"
  val KAMON_ENV_HOST = s"$KAMON_ENV.host"
  val KAMON_PROMETHEUS = s"$KAMON.prometheus"
  val KAMON_PROMETHEUS_START_HTTP_SERVER = s"$KAMON_PROMETHEUS.start-embedded-http-server"

  private val METRICS = s"$VERITY.metrics"
  val METRICS_ENABLED = s"$METRICS.enabled"
  val METRICS_UTIL_FILTERS = s"$METRICS.util.filters"
  val RESET_METRICS_NAME_SUFFIX = s"$METRICS.reset-metrics-suffix"
  val LIBINDY_METRICS_COLLECTION_FREQUENCY = s"$METRICS.libindy-metrics-collection-frequency"
  private val ACTIVITY_TRACKING = s"$METRICS.activity-tracking"
  val ACTIVE_USER_METRIC = s"$ACTIVITY_TRACKING.active-user"
  val ACTIVE_RELATIONSHIP_METRIC = s"$ACTIVITY_TRACKING.active-relationships"
  private val PROTOCOL_METRIC = s"$METRICS.protocol"
  val PROTOCOL_TAG = s"$PROTOCOL_METRIC.tags"
  val PROTOCOL_TAG_USES_SPONSOR = s"$PROTOCOL_TAG.uses-sponsor"
  val PROTOCOL_TAG_USES_SPONSEE = s"$PROTOCOL_TAG.uses-sponsee"

  private val METRICS_TARGET  = s"$METRICS.target"
  val METRICS_TARGET_AKKA_SYSTEM = s"$METRICS_TARGET.akka-system"
  val METRICS_TARGET_AKKA_GROUP = s"$METRICS_TARGET.akka-group"
  val METRICS_TARGET_AKKA_ACTOR = s"$METRICS_TARGET.akka-actor"
  val METRICS_TARGET_EXECUTOR_TASKS = s"$METRICS_TARGET.executor-tasks"
  val METRICS_TARGET_EXECUTOR_POOL = s"$METRICS_TARGET.executor-pool"
  val METRICS_TARGET_EXECUTOR_THREADS = s"$METRICS_TARGET.executor-threads"
  val METRICS_TARGET_EXECUTOR_QUEUE = s"$METRICS_TARGET.executor-queue"
  val METRICS_TARGET_CONNECTOR = s"$METRICS_TARGET.connector"

  val METRICS_LATENCY_RECORDING_HISTOGRAM = s"$METRICS.latency-recording.histogram.enabled"
  val METRICS_LATENCY_RECORDING_SPAN = s"$METRICS.latency-recording.span.enabled"

  private val REST_API = s"$VERITY.rest-api"
  val REST_API_ENABLED = s"$REST_API.enabled"

  val RESOURCE_USAGE_RULES = s"$VERITY.resource-usage-rules"
  val USAGE_RULES = s"$RESOURCE_USAGE_RULES.usage-rules"
  val VIOLATION_ACTION = s"$RESOURCE_USAGE_RULES.violation-action"
  val RULE_TO_TOKENS = s"$RESOURCE_USAGE_RULES.rule-to-tokens"
  val WHITELISTED_TOKENS = s"$RESOURCE_USAGE_RULES.whitelisted-tokens"
  val BLACKLISTED_TOKENS = s"$RESOURCE_USAGE_RULES.blacklisted-tokens"

  val AKKA_MNGMNT_HTTP = "akka.management.http"
  val AKKA_MNGMNT_HTTP_ENABLED = s"$AKKA_MNGMNT_HTTP.enabled"
  val AKKA_MNGMNT_HTTP_HOSTNAME = s"$AKKA_MNGMNT_HTTP.hostname"
  val AKKA_MNGMNT_HTTP_PORT = s"$AKKA_MNGMNT_HTTP.port"
  val AKKA_MNGMNT_HTTP_API_CREDS = s"$AKKA_MNGMNT_HTTP.api-creds"

  val AKKA_SHARDING_REGION_NAME = "akka.sharding-region-name"
  val AKKA_SHARDING_REGION_NAME_USER_AGENT = "akka.sharding-region-name.user-agent"
  val AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE = "akka.sharding-region-name.user-agent-pairwise"

  private val TIMEOUT = s"$VERITY.timeout"
  val TIMEOUT_GENERAL_ASK_TIMEOUT_IN_SECONDS = s"$TIMEOUT.general-ask-timeout-in-seconds"
  val TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS = s"$TIMEOUT.general-actor-ask-timeout-in-seconds"
  val TIMEOUT_ACTOR_REF_RESOLVE_TIMEOUT_IN_SECONDS = s"$TIMEOUT.actor-ref-resolve-timeout-in-seconds"
  val TIMEOUT_SMS_SERVICE_ASK_TIMEOUT_IN_SECONDS = s"$TIMEOUT.sms-service-ask-timeout-in-seconds"
  val TIMEOUT_SERVICE_SHUTDOWN_TIMEOUT_IN_SECONDS = s"$TIMEOUT.service-shutdown-timeout-in-seconds"

  private val ACTOR_DISPATCHER_NAME = "akka.actor.dispatchers"
  val AGENCY_AGENT_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.agency-agent-dispatcher"
  val AGENCY_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.agency-agent-pairwise-dispatcher"
  val USER_AGENT_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.user-agent-dispatcher"
  val USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.user-agent-pairwise-dispatcher"
  val ACTIVITY_TRACKER_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.activity-tracker-dispatcher"
  val WALLET_ACTOR_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.wallet-dispatcher"
  val ASYNC_OP_EXECUTOR_ACTOR_DISPATCHER_NAME = s"$ACTOR_DISPATCHER_NAME.wallet-dispatcher"

  val APP_STATE_MANAGER = s"$VERITY.app-state-manager"
  val APP_STATE_MANAGER_STATE = s"$APP_STATE_MANAGER.state"
  val APP_STATE_MANAGER_STATE_INITIALIZING = s"$APP_STATE_MANAGER_STATE.initializing"
  val APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_COUNT = s"$APP_STATE_MANAGER_STATE_INITIALIZING.max-retry-count"
  val APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_DURATION = s"$APP_STATE_MANAGER_STATE_INITIALIZING.max-retry-duration"
  val APP_STATE_MANAGER_STATE_DRAINING = s"$APP_STATE_MANAGER_STATE.draining"
  val APP_STATE_MANAGER_STATE_DRAINING_DELAY_BEFORE_LEAVING_CLUSTER_IN_SECONDS = s"$APP_STATE_MANAGER_STATE_DRAINING.delay-before-leave"
  val APP_STATE_MANAGER_STATE_DRAINING_DELAY_BETWEEN_STATUS_CHECKS_IN_SECONDS = s"$APP_STATE_MANAGER_STATE_DRAINING.delay-between-status-checks"
  val APP_STATE_MANAGER_STATE_DRAINING_MAX_STATUS_CHECK_COUNT = s"$APP_STATE_MANAGER_STATE_DRAINING.max-status-check-count"

  val ITEM_CONTAINER = s"$VERITY.item-container"
  val ITEM_CONTAINER_SCHEDULED_JOB = s"$ITEM_CONTAINER.scheduled-job"
  val ITEM_CONTAINER_SCHEDULED_JOB_INTERVAL_IN_SECONDS = s"$ITEM_CONTAINER_SCHEDULED_JOB.interval-in-seconds"

  val ITEM_CONTAINER_MIGRATION = s"$ITEM_CONTAINER.migration"
  val ITEM_CONTAINER_MIGRATION_CHUNK_SIZE = s"$ITEM_CONTAINER_MIGRATION.chunk-size"
  val ITEM_CONTAINER_MIGRATION_CHECK_RESULT_HISTORY_SIZE = s"$ITEM_CONTAINER_MIGRATION.check-result-history-size"

  val ITEM_WATCHER = s"$VERITY.item-watcher"
  val ITEM_WATCHER_BATCH_SIZE = s"$ITEM_WATCHER.batch-size"

  val FAILED_MSG_RETRIER = s"$VERITY.failed-msg-retrier"
  val FAILED_MSG_RETRIER_BATCH_SIZE = s"$FAILED_MSG_RETRIER.batch-size"
  val FAILED_MSG_RETRIER_MAX_RETRY_COUNT = s"$FAILED_MSG_RETRIER.max-retry-count"

  private val AGENT_ACTOR_WATCHER = s"$VERITY.actor.watcher"
  val AGENT_ACTOR_WATCHER_ENABLED = s"$AGENT_ACTOR_WATCHER.enabled"
  val AGENT_ACTOR_WATCHER_VERSION = s"$AGENT_ACTOR_WATCHER.version"
  val AGENT_ACTOR_WATCHER_SCHEDULED_JOB = s"$AGENT_ACTOR_WATCHER.scheduled-job"
  val AGENT_ACTOR_WATCHER_SCHEDULED_JOB_INTERVAL_IN_SECONDS = s"$AGENT_ACTOR_WATCHER_SCHEDULED_JOB.interval-in-seconds"

  val USER_AGENT_PAIRWISE_ACTOR = s"$VERITY.user-agent-pairwise-actor"
  val USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB = s"$USER_AGENT_PAIRWISE_ACTOR.scheduled-job"
  val USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS = s"$USER_AGENT_PAIRWISE_ACTOR_SCHEDULED_JOB.interval-in-seconds"

  val VERITY_AGENT = s"$VERITY.agent"

  val AGENT_STATE_MESSAGES_CLEANUP = s"$VERITY_AGENT.state.messages.cleanup"
  val AGENT_STATE_MESSAGES_CLEANUP_ENABLED = s"$AGENT_STATE_MESSAGES_CLEANUP.enabled"
  val AGENT_STATE_MESSAGES_CLEANUP_DAYS_TO_RETAIN_DELIVERED_MSGS = s"$AGENT_STATE_MESSAGES_CLEANUP.days-to-retain-delivered-msgs"
  val AGENT_STATE_MESSAGES_CLEANUP_TOTAL_MSGS_TO_RETAIN = s"$AGENT_STATE_MESSAGES_CLEANUP.total-msgs-to-retain"

  val PROVISIONING = s"$VERITY.provisioning"

  val PROVISIONING_SPONSORS = s"$PROVISIONING.sponsors"

  val AGENT_AUTHENTICATION = s"$VERITY_AGENT.authentication"
  val AGENT_AUTHENTICATION_ENABLED = s"$AGENT_AUTHENTICATION.enabled"
  val AGENT_AUTHENTICATION_KEYS = s"$AGENT_AUTHENTICATION.keys"

  val RECEIVE_TIMEOUT_SECONDS = "receive-timeout-seconds"
  val RECOVER_FROM_SNAPSHOT = "recover-from-snapshots"
  val SNAPSHOT_AFTER_N_EVENTS = "snapshot.after-n-events"
  val KEEP_N_SNAPSHOTS = "snapshot.keep-n-snapshots"
  val DELETE_EVENTS_ON_SNAPSHOTS = "snapshot.delete-events-on-snapshots"
  val PASSIVATE_TIME_IN_SECONDS = "passivate-time-in-seconds"

  val SUPERVISOR = "supervisor"
  val SUPERVISOR_ENABLED = s"$SUPERVISOR.enabled"
  val BACKOFF_SUPERVISOR = s"$SUPERVISOR.backoff"
  val BACKOFF_SUPERVISOR_STRATEGY = s"$BACKOFF_SUPERVISOR.strategy"
  val BACKOFF_SUPERVISOR_MIN_SECONDS = s"$BACKOFF_SUPERVISOR.min-seconds"
  val BACKOFF_SUPERVISOR_MAX_SECONDS = s"$BACKOFF_SUPERVISOR.max-seconds"
  val BACKOFF_SUPERVISOR_RANDOM_FACTOR = s"$BACKOFF_SUPERVISOR.random-factor"
  val BACKOFF_SUPERVISOR_MAX_NR_OF_RETRIES = s"$BACKOFF_SUPERVISOR.max-nr-of-retries"

  private val NON_PERSISTENT_ACTOR = s"$VERITY.non-persistent-actor"
  val NON_PERSISTENT_ACTOR_BASE = s"$NON_PERSISTENT_ACTOR.base"
  val NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS = s"$NON_PERSISTENT_ACTOR_BASE.WalletActor.$PASSIVATE_TIME_IN_SECONDS"


  private val PERSISTENT_ACTOR = s"$VERITY.persistent-actor"
  val PERSISTENT_ACTOR_BASE = s"$PERSISTENT_ACTOR.base"
  val PERSISTENT_SINGLETON_CHILDREN = s"$PERSISTENT_ACTOR.singleton-children"
  val PERSISTENT_PROTOCOL_CONTAINER = s"$PERSISTENT_ACTOR.protocol-container"

  val PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS = s"$PERSISTENT_ACTOR_BASE.$RECEIVE_TIMEOUT_SECONDS"
  val PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS = s"$PERSISTENT_SINGLETON_CHILDREN.$RECEIVE_TIMEOUT_SECONDS"
  val PERSISTENT_PROTOCOL_CONTAINER_RECEIVE_TIMEOUT_SECONDS = s"$PERSISTENT_PROTOCOL_CONTAINER.$RECEIVE_TIMEOUT_SECONDS"

  val PERSISTENT_PROTOCOL_WARN_RECOVERY_TIME_MILLISECONDS = s"$PERSISTENT_ACTOR.warn-recovery-time-milliseconds"

  val AGENT_ACTOR_STATE_CLEANUP = s"$VERITY_AGENT.actor-state-cleanup"
  val AGENT_ACTOR_STATE_CLEANUP_ENABLED = s"$AGENT_ACTOR_STATE_CLEANUP.enabled"

  val AAS_CLEANUP_MANAGER = s"$AGENT_ACTOR_STATE_CLEANUP.manager"

  val AAS_CLEANUP_MANAGER_REGISTRATION = s"$AAS_CLEANUP_MANAGER.registration"
  val AAS_CLEANUP_MANAGER_REGISTRATION_BATCH_SIZE = s"$AAS_CLEANUP_MANAGER_REGISTRATION.batch-size"
  val AAS_CLEANUP_MANAGER_REGISTRATION_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS =
    s"$AAS_CLEANUP_MANAGER_REGISTRATION.batch-item-sleep-interval-in-millis"

  val AAS_CLEANUP_MANAGER_PROCESSOR = s"$AAS_CLEANUP_MANAGER.processor"
  val AAS_CLEANUP_MANAGER_PROCESSOR_BATCH_SIZE = s"$AAS_CLEANUP_MANAGER_PROCESSOR.batch-size"
  val AAS_CLEANUP_MANAGER_PROCESSOR_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS =
    s"$AAS_CLEANUP_MANAGER_PROCESSOR.batch-item-sleep-interval-in-millis"

  val AAS_CLEANUP_MANAGER_SCHEDULED_JOB = s"$AAS_CLEANUP_MANAGER.scheduled-job"
  val AAS_CLEANUP_MANAGER_SCHEDULED_JOB_INTERVAL_IN_SECONDS =
    s"$AAS_CLEANUP_MANAGER_SCHEDULED_JOB.interval-in-seconds"

  val AAS_CLEANUP_EXECUTOR = s"$AGENT_ACTOR_STATE_CLEANUP.executor"
  val AAS_CLEANUP_EXECUTOR_BATCH_SIZE = s"$AAS_CLEANUP_EXECUTOR.batch-size"
  val AAS_CLEANUP_EXECUTOR_SCHEDULED_JOB = s"$AAS_CLEANUP_EXECUTOR.scheduled-job"
  val AAS_CLEANUP_EXECUTOR_SCHEDULED_JOB_INTERVAL_IN_SECONDS =
    s"$AAS_CLEANUP_EXECUTOR_SCHEDULED_JOB.interval-in-seconds"

  val MIGRATE_THREAD_CONTEXTS = s"$VERITY_AGENT.migrate-thread-contexts"
  val MIGRATE_THREAD_CONTEXTS_ENABLED = s"$MIGRATE_THREAD_CONTEXTS.enabled"
  val MIGRATE_THREAD_CONTEXTS_MAX_ATTEMPT_PER_PINST_PROTO_REF = s"$MIGRATE_THREAD_CONTEXTS.max-attempt-per-pinst-proto-ref"
  val MIGRATE_THREAD_CONTEXTS_BATCH_SIZE = s"$MIGRATE_THREAD_CONTEXTS.batch-size"
  val MIGRATE_THREAD_CONTEXTS_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS =
    s"$MIGRATE_THREAD_CONTEXTS.batch-item-sleep-interval-in-millis"

  val MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB = s"$MIGRATE_THREAD_CONTEXTS.scheduled-job"
  val MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB_INTERVAL_IN_SECONDS =
    s"$MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB.interval-in-seconds"

  val VERITY_ENDORSER = s"$VERITY_AGENT.endorser"
  val VERITY_ENDORSER_DEFAULT_DID = s"$VERITY_ENDORSER.did"

  val LOGGING = s"$VERITY.logging"
  val LOGGING_IGNORE_FILTER_NAMES = s"$LOGGING.ignore-logger-filter.logger-name-contains"

  val MSG_SENDING_SVC = s"$SERVICES.msg-sending-svc"
  val AKKA_HTTP_MSG_SENDING_SVC = s"$MSG_SENDING_SVC.akka-http-svc"
  val AKKA_HTTP_MSG_SENDING_SVC_API_TYPE = s"$AKKA_HTTP_MSG_SENDING_SVC.api-type"
}

object CommonConfig extends CommonConfig
