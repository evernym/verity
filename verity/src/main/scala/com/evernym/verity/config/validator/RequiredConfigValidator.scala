package com.evernym.verity.config.validator

import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.validator.base._
import com.evernym.verity.constants.Constants.{WALLET_TYPE_DEFAULT, WALLET_TYPE_MYSQL, YES}
import com.typesafe.config.Config

//checks if given configs are present, if not, then throw appropriate exception
// if present, then validates the config value

object RequiredConfigValidator extends ConfigValidatorCreator {
  override def create(config: Config): ConfigValidator = new RequiredConfigValidator(config)
}

class RequiredConfigValidator(val config: Config) extends RequiredConfigValidatorBase

trait RequiredConfigValidatorBase extends ConfigValidatorBase {

  override def configsToBeValidated(): Set[ConfDetail] =
    commonConfigsToBeValidated ++ conditionalConfigsToBeValidated

  def conditionalConfigsToBeValidated: Set[ConfDetail] = {
    if (getConfigBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {
      Set (
        ConfDetail(PUSH_NOTIF_GENERAL_MSG_TITLE_TEMPLATE),
        ConfDetail(PUSH_NOTIF_DEFAULT_LOGO_URL),
        ConfDetail(PUSH_NOTIF_DEFAULT_SENDER_NAME),
        ConfDetail(CONNECT_ME_MAPPED_URL_TEMPLATE)
      )
    } else Set.empty
  }

  def commonConfigsToBeValidated(): Set[ConfDetail] = Set (
    ConfDetail(VERITY_ENDPOINT_HOST),
    ConfDetail(VERITY_ENDPOINT_PORT),
    ConfDetail(VERITY_ENDPOINT_PATH_PREFIX),

    ConfDetail(HTTP_INTERFACE),
    ConfDetail(HTTP_PORT),

    ConfDetail(KEYSTORE_LOCATION, depConfDetail=Option(DepConfDetail(HTTP_SSL_PORT))),
    ConfDetail(KEYSTORE_PASSWORD, depConfDetail=Option(DepConfDetail(KEYSTORE_LOCATION))),

    ConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST),
    ConfDetail(URL_MAPPER_SVC_ENDPOINT_PORT, depConfDetail=Option(DepConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST))),
    ConfDetail(URL_MAPPER_SVC_ENDPOINT_PATH_PREFIX, depConfDetail=Option(DepConfDetail(URL_MAPPER_SVC_ENDPOINT_HOST))),

    ConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER),

    ConfDetail(TWILIO_TOKEN, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_ACCOUNT_SID, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_DEFAULT_NUMBER, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),
    ConfDetail(TWILIO_APP_NAME, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("TW")))),

    ConfDetail(OPEN_MARKET_USER_NAME, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),
    ConfDetail(OPEN_MARKET_PASSWORD, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),
    ConfDetail(OPEN_MARKET_SERVICE_ID, depConfDetail=Option(DepConfDetail(SMS_EXTERNAL_SVC_PREFERRED_ORDER, Option("OM")))),

    ConfDetail(LIB_INDY_LIBRARY_DIR_LOCATION),
    ConfDetail(LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION),
    ConfDetail(LIB_INDY_LEDGER_POOL_NAME),
    ConfDetail(LIB_INDY_WALLET_TYPE, allowedValues = Set(WALLET_TYPE_DEFAULT, WALLET_TYPE_MYSQL)),

    ConfDetail(SALT_WALLET_NAME),
    ConfDetail(SALT_WALLET_ENCRYPTION),
    ConfDetail(SALT_EVENT_ENCRYPTION),

    ConfDetail(SECRET_ROUTING_AGENT),
    ConfDetail(SECRET_URL_STORE),
    ConfDetail(SECRET_KEY_VALUE_MAPPER),
    ConfDetail(SECRET_TOKEN_TO_ACTOR_ITEM_MAPPER),
    ConfDetail(SECRET_RESOURCE_WARNING_STATUS_MNGR),
    ConfDetail(SECRET_RESOURCE_BLOCKING_STATUS_MNGR),

    ConfDetail(SMS_MSG_TEMPLATE_INVITE_URL),
    ConfDetail(SMS_MSG_TEMPLATE_OFFER_CONN_MSG),

    ConfDetail(CONN_REQ_MSG_EXPIRATION_TIME_IN_SECONDS),

    ConfDetail(INTERNAL_API_ALLOWED_FROM_IP_ADDRESSES),

    ConfDetail(KAMON_ENV_HOST),
    ConfDetail(KAMON_PROMETHEUS_START_HTTP_SERVER, allowedValues = Set("no")),

    ConfDetail(AKKA_SHARDING_REGION_NAME_USER_AGENT),
    ConfDetail(AKKA_SHARDING_REGION_NAME_USER_AGENT_PAIRWISE),

    ConfDetail(WALLET_STORAGE_READ_HOST, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_WRITE_HOST, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_HOST_PORT, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_CRED_USERNAME, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_CRED_PASSWORD, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),
    ConfDetail(WALLET_STORAGE_DB_NAME, depConfDetail=Option(DepConfDetail(LIB_INDY_WALLET_TYPE, Option(WALLET_TYPE_MYSQL)))),

    ConfDetail("akka.actor.serializers.protoser", allowedValues = Set("com.evernym.verity.actor.serializers.ProtoBufSerializer")),
    ConfDetail("akka.actor.serializers.kryo-akka", allowedValues = Set("com.twitter.chill.akka.AkkaSerializer")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedStateMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.DeprecatedMultiEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.PersistentMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.PersistentMultiEventMsg\"", allowedValues = Set("protoser")),
    ConfDetail("akka.actor.serialization-bindings.\"com.evernym.verity.actor.ActorMessage\"", allowedValues = Set("kryo-akka"))
  )

  override val validationType: String = "required configuration validation"
  override val required = true
}
