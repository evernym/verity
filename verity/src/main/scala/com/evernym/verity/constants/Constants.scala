package com.evernym.verity.constants

object Constants {

  val GET_AGENCY_VER_KEY_FROM_POOL = true

  val EMPTY_STRING = ""
  val NULL_CHARACTER = "\u0000"
  val TAB = "\t"
  val FORM_FEED = "\f"
  val BACKSPACE = "\b"
  val CARRIAGE_RETURN = "\r"
  val BACKSLASH = """\"""
  val NEW_LINE = "\n"
  val SPACE = " "
  val DOUBLE_QUOTE = "\""
  val DOUBLE_QUOTE_FOR_BANDWIDTH = "\\\\\""
  val FORWARD_SLASH = "/"

  val UTC = "UTC"
  val YES = "Y"
  val NO = "N"

  val NOT_AVAILABLE = "not available"

  val ASC = "asc"
  val DESC = "desc"

  val VALID_IDENTIFIER_LENGTH_RANGE: Range = 21 to 23
  val VALID_TOKEN_LENGTH_RANGE: Range = 7 to 7
  val VALID_HASHED_URL_LENGTH_RANGE: Range = 8 to 8

  val VALID_DID_BYTE_LENGTH = 16
  val VALID_VER_KEY_BYTE_LENGTH = 32

  //timeouts
  val DEFAULT_GENERAL_RESPONSE_TIMEOUT_IN_SECONDS = 30
  val DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS = 30
  val DEFAULT_GENERAL_ACTOR_REF_RESOLVE_TIMEOUT_IN_SECONDS = 15

  val SER_TYPE_MSG_PACK = "msgpack"
  val AGENCY_DID_KEY = "AGENCY_DID"   //DON'T change this constant value once it is used

  val MSG_TYPE = "msgType"
  val TYPE = "type"
  val UID = "uid"
  val VALUE = "value"
  val TITLE = "title"
  val DETAIL = "detail"
  val BODY = "body"
  val NAME_KEY = "name"
  val LOGO_URL_KEY = "logoUrl"
  val TOKEN = "token"
  val BASE_URL = "baseUrl"
  val APP_URL_LINK = "appUrlLink"
  val PUBLIC_DID = "publicDid"

  val PUSH_NOTIF_BODY_TEMPLATE = "pushNotifBodyTemplate"
  val TARGET_NAME = "targetName"
  val SOURCE_ID = "sourceId"
  val INCLUDE_PUBLIC_DID = "includePublicDID"
  val SENDER_NAME = "senderName"
  val SENDER_LOGO_URL = "senderLogoUrl"
  val REQUESTER_NAME = "requesterName"
  val DEFAULT_INVITE_RECEIVER_USER_NAME = "there"
  val NOTIFICATION = "notification"
  val CONTENT_AVAILABLE = "content_available"

  val PHONE_NO = "phoneNumber"
  val TEXT = "text"

  //push com method related
  val PUSH_NOTIF_MSG_TYPE = MSG_TYPE
  val PUSH_NOTIF_MSG_TITLE = "pushNotifMsgTitle"
  val PUSH_NOTIF_MSG_TEXT = "pushNotifMsgText"
  val BADGE_COUNT = "badge"
  val PUSH_COM_METHOD = "pushComMethod"
  val COLLAPSE_KEY = "collapse_key"
  val DATA = "data"
  val TO = "to"

  val URL = "url"
  val HASHED_URL = "hashedUrl"

  val FOR_DID = "forDID"

  val THREAD_ID = "thid"
  val PARENT_THREAD_ID = "pthid"
  val SENDER_ORDER = "sender_order"
  val RECEIVED_ORDERS = "received_orders"
  val FOR_RELATIONSHIP = "~for_relationship"

  //event encryption related

  val COM_METHOD_TYPE_PUSH = 1
  val COM_METHOD_TYPE_HTTP_ENDPOINT = 2
  val COM_METHOD_TYPE_FWD_PUSH = 3
  val COM_METHOD_TYPE_SPR_PUSH = 4

  val API_KEY_HTTP_HEADER = "X-API-KEY"
  val CLIENT_REQUEST_ID_HTTP_HEADER = "API-REQUEST-ID"

  val MSG_PACK_VERSION = "msg-pack-version"

  val CLIENT_IP_ADDRESS = "client_ip"

  val LIBINDY_LEGACY_FLAVOR = "legacy"

  val WALLET_TYPE_DEFAULT = "default"
  val WALLET_TYPE_MYSQL = "mysql"
  val WALLET_KEY_DERIVATION_METHOD = "RAW"

  val LEDGER_TXN_PROTOCOL_V1 = 1
  val LEDGER_TXN_PROTOCOL_V2 = 2

  val PUSH_COM_METHOD_NOT_REGISTERED_ERROR = "NotRegistered"
  val PUSH_COM_METHOD_INVALID_REGISTRATION_ERROR = "InvalidRegistration"
  val PUSH_COM_METHOD_MISMATCH_SENDER_ID_ERROR = "MismatchSenderId"
  val PUSH_COM_METHOD_WARN_ON_ERROR_LIST = Set (
    PUSH_COM_METHOD_INVALID_REGISTRATION_ERROR,
    PUSH_COM_METHOD_MISMATCH_SENDER_ID_ERROR,
    PUSH_COM_METHOD_NOT_REGISTERED_ERROR
  )

  val BUCKET_ID_INDEFINITE_TIME = -1

  val DEFAULT_INVITE_SENDER_NAME = "Verity Org"
  val DEFAULT_INVITE_SENDER_LOGO_URL = "https://robohash.org/234"

  val UNKNOWN_OTHER_ID = "unknown-other-id"
  val UNKNOWN_SENDER_PARTICIPANT_ID = "unknown-sender-participant-id"
  val UNKNOWN_RECIP_PARTICIPANT_ID = "unknown-recipient-participant-id"

  val `@MSG` = "@msg"
  val FORMAT = "fmt"
  val FORMAT_TYPE_JSON = "json"
  val PAYLOAD_WRAPPER_MESSAGE_TYPE = "MESSAGE"

  val SMS_PROVIDER_ID_BANDWIDTH = "BW"
  val SMS_PROVIDER_ID_TWILIO = "TW"
  val SMS_PROVIDER_ID_OPEN_MARKET = "OM"
  val SMS_PROVIDER_ID_INFO_BIP = "IB"

  val URL_SHORTENER_PROVIDER_ID_YOURLS = "YOURLS"
  val URL_SHORTENER_PROVIDER_ID_S3_SHORTENER = "S3-SHORTENER"
  val URL_SHORTENER_PROVIDER_ID_IN_IDENTITY = "IDENTITY"
}
