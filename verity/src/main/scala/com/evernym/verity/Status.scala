package com.evernym.verity

import com.evernym.verity.config.CommonConfig
import com.evernym.verity.Exceptions.InternalServerErrorException
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import scala.language.implicitConversions

object Status extends Enumeration {

  lazy val logger: Logger = getLoggerByClass(getClass)

  import StatusCodePrefix._

  val TIMEOUT                                     = statusDetail(GNR_PREFIX, 100, "system is unusually busy, try again later")
  val VALIDATION_FAILED                           = statusDetail(GNR_PREFIX, 101, "validation failed")
  val UNHANDLED                                   = statusDetail(GNR_PREFIX, 105, "unhandled error")
  val FORBIDDEN                                   = statusDetail(GNR_PREFIX, 106, "forbidden")
  val SIGNATURE_VERIF_FAILED                      = statusDetail(GNR_PREFIX, 107, "signature verification failed")
  val UNAUTHORIZED                                = statusDetail(GNR_PREFIX, 108, "unauthorized")

  val BAD_REQUEST                                 = statusDetail(GNR_PREFIX, 111, "bad request")
  val RESOURCE_NOT_CREATED                        = statusDetail(GNR_PREFIX, 112, "resource not created")
  val RESOURCE_NOT_FOUND                          = statusDetail(GNR_PREFIX, 113, "requested resource not found")
  val DATA_NOT_FOUND                              = statusDetail(GNR_PREFIX, 114, "requested data not found")
  val INVALID_VALUE                               = statusDetail(GNR_PREFIX, 115, "invalid value")
  val ALREADY_EXISTS                              = statusDetail(GNR_PREFIX, 116, "already exists")
  val MISSING_REQ_FIELD                           = statusDetail(GNR_PREFIX, 117, "missing required field")
  val EMPTY_VALUE_FOR_OPTIONAL_FIELD              = statusDetail(GNR_PREFIX, 118, "empty value given for optional field")
  val UNKNOWN_FIELDS_FOUND                        = statusDetail(GNR_PREFIX, 119, "unknown field(s) found")
  val UNSUPPORTED_MSG_TYPE                        = statusDetail(GNR_PREFIX, 120, "message type not supported")
  val UNKNOWN_MSG_SENDER                          = statusDetail(GNR_PREFIX, 121, "unknown/unauthorized message sender")
  val UNSUPPORTED_MSG_VERSION                     = statusDetail(GNR_PREFIX, 122, "message version not supported")
  val USAGE_BLOCKED                               = statusDetail(GNR_PREFIX, 123, "usage blocked")
  val CONFIG_LOADING_FAILED                       = statusDetail(GNR_PREFIX, 124, "config not loaded")
  val NOT_IMPLEMENTED                             = statusDetail(GNR_PREFIX, 125, "not implemented")
  val REMOTE_ENDPOINT_NOT_FOUND                   = statusDetail(GNR_PREFIX, 126, "system is experiencing some issue, try again later")
  val CONFIG_VALIDATION_ERROR                     = statusDetail(GNR_PREFIX, 127, "config validation error")
  val AGENT_SERVICE_STARTED                       = statusDetail(GNR_PREFIX, 128, "agent service started")
  val ACCEPTING_TRAFFIC                           = statusDetail(GNR_PREFIX, 129, "accepting traffic")
  val NOT_ACCEPTING_TRAFFIC                       = statusDetail(GNR_PREFIX, 130, "not accepting traffic")
  val S3_FAILURE                                  = statusDetail(GNR_PREFIX, 131, "s3 failure")
  val APP_STATUS_UPDATE_MANUAL                    = statusDetail(GNR_PREFIX, 132, "app status update manual")

  val SMS_SENDING_FAILED                          = statusDetail(SMS_PREFIX, 101, "sms sending failed")

  val AGENCY_ALREADY_INITIALIZED                  = statusDetail(AIS_PREFIX, 101, "agency already initialized")
  val AGENCY_NOT_INITIALIZED                      = statusDetail(AIS_PREFIX, 102, "agency not yet initialized")

  val CONN_STATUS_ALREADY_CONNECTED               = statusDetail(CS_PREFIX, 101, "already connected")
  val CONN_STATUS_NOT_CONNECTED                   = statusDetail(CS_PREFIX, 102, "not connected")
  val CONN_STATUS_DELETED                         = statusDetail(CS_PREFIX, 103, "connection is deleted")
  val PAIRWISE_KEYS_ALREADY_IN_WALLET             = statusDetail(CS_PREFIX, 104, "pairwise key already in wallet")

  val ALREADY_REGISTERED                          = statusDetail(ARS_PREFIX, 101, "already registered")
  val NOT_REGISTERED                              = statusDetail(ARS_PREFIX, 102, "not registered")

  val AGENT_ALREADY_CREATED                       = statusDetail(ACS_PREFIX, 101, "agent already initialized")
  val AGENT_NOT_YET_CREATED                       = statusDetail(ACS_PREFIX, 102, "agent not yet created")
  //TODO: can remove once Provisioning 0.5 and 0.6 are removed from the code
  val PROVISIONING_PROTOCOL_DEPRECATED            = statusDetail(ACS_PREFIX, 103, "provisioning protocol deprecated: needs 0.7 or greater")

  val NO_ACCEPTED_CONN_REQ                        = statusDetail(CRS_PREFIX, 101, "no accepted connection request")
  val ACCEPTED_CONN_REQ_EXISTS                    = statusDetail(CRS_PREFIX, 102, "only one accepted connection request can exists with one pairwise relationship")
  val REDIRECTED_CONN_REQ_EXISTS                  = statusDetail(CRS_PREFIX, 103, "redirect connection request already exists")

  val MSG_STATUS_CREATED                          = statusDetail(MS_PREFIX, 101, "message created")
  val MSG_STATUS_SENT                             = statusDetail(MS_PREFIX, 102, "message sent")
  val MSG_STATUS_RECEIVED                         = statusDetail(MS_PREFIX, 103, "message received")
  val MSG_STATUS_ACCEPTED                         = statusDetail(MS_PREFIX, 104, "message accepted")
  val MSG_STATUS_REJECTED                         = statusDetail(MS_PREFIX, 105, "message rejected")
  val MSG_STATUS_REVIEWED                         = statusDetail(MS_PREFIX, 106, "message reviewed")
  val MSG_STATUS_REDIRECTED                       = statusDetail(MS_PREFIX, 107, "message redirected")

  val MSG_VALIDATION_ERROR_EXPIRED                = statusDetail(VES_PREFIX, 101, "expired")
  val MSG_VALIDATION_ERROR_ALREADY_ANSWERED       = statusDetail(VES_PREFIX, 102, "already answered")
  val KEY_ALREADY_CREATED                         = statusDetail(VES_PREFIX, 103, "key already created")
  val CONNECTION_DOES_NOT_EXIST                   = statusDetail(VES_PREFIX, 104, "connection does not exist")
  val NO_OWNER_DETAIL_EXISTS                      = statusDetail(VES_PREFIX, 105, "no owner detail exists")
  val KEY_DELEGATED_PROOF_NOT_FOUND               = statusDetail(VES_PREFIX, 106, "delegated agency key not found")

  val MSG_DELIVERY_STATUS_PENDING                 = statusDetail(MDS_PREFIX, 101, "message delivery is pending")
  val MSG_DELIVERY_STATUS_SENT                    = statusDetail(MDS_PREFIX, 102, "message sent successfully")
  val MSG_DELIVERY_STATUS_FAILED                  = statusDetail(MDS_PREFIX, 103, "message delivery failed")

  val LEDGER_DID_ALREADY_EXISTS_WITH_DIFF_VER_KEY = statusDetail(LVS_PREFIX, 101, "DID already registered in ledger with different ver key")

  val METRICS_CAPTURING_STATUS_NOT_ENABLED        = statusDetail(MCS_PREFIX, 101, "metrics capturing not enabled")

  val AEPSS_ITEM_COLLECTION_SIZE_LIMIT_EXCEEDED   = statusDetail(AEPSS_PREFIX, 101, "system is unusually busy, try again later")
  val AEPSS_OPERATION_LIMIT_EXCEEDED              = statusDetail(AEPSS_PREFIX, 102, "system is unusually busy, try again later")
  val AEPSS_TABLE_PROV_THROUGHPUT_EXCEEDED        = statusDetail(AEPSS_PREFIX, 103, "system is unusually busy, try again later")
  val AEPSS_THROTTLING_EXCEPTION                  = statusDetail(AEPSS_PREFIX, 104, "system is unusually busy, try again later")
  val AEPSS_UNRECOGNIZED_CLIENT_EXCEPTION         = statusDetail(AEPSS_PREFIX, 105, "system is unusually busy, try again later")
  val AEPSS_NO_RESPONSE_FROM_SERVICE              = statusDetail(AEPSS_PREFIX, 106, "system is experiencing some issue, try again later")
  val AEPSS_RESOURCE_NOT_FOUND                    = statusDetail(AEPSS_PREFIX, 107, "resource (tables) not created")
  val AEPSS_DYNAMODB_JOURNAL_REJECTION            = statusDetail(AEPSS_PREFIX, 108, "dynamodb journal rejection")
  val AEPSS_DYNAMODB_JOURNAL_FAILURE              = statusDetail(AEPSS_PREFIX, 109, "dynamodb journal failure")

  val LEDGER_UNKNOWN_ERROR                        = statusDetail(LPSS_PREFIX, 100, "Unknown error in interaction with ledger")
  val LEDGER_POOL_NO_RESPONSE                     = statusDetail(LPSS_PREFIX, 101, "system is experiencing some issue, try again later")
  val LEDGER_DATA_INVALID                         = statusDetail(LPSS_PREFIX, 102, "Data returned from ledger is invalid")
  val LEDGER_NOT_CONNECTED                        = statusDetail(LPSS_PREFIX, 103, "No connection to the ledger")
  val LEDGER_REQUEST_INCOMPLETABLE                = statusDetail(LPSS_PREFIX, 104, "Unable to complete request to ledger")
  val LEDGER_UNKNOWN_REJECT_ERROR                 = statusDetail(LPSS_PREFIX, 105, "Unknown REJECT error in interaction with ledger")
  val LEDGER_WRITE_REJECTED                       = statusDetail(LPSS_PREFIX, 106, "Ledger write rejected")

  val INDY_SDK_UNHANDLED_EXCEPTION                = statusDetail(ISDK_PREFIX, 100, "Unhandled Indy SDK exception")

  val EVENT_ENCRYPTION_FAILED                     = statusDetail(EE_PREFIX, 101, "system is experiencing some issue, try again later")
  val EVENT_DECRYPTION_FAILED                     = statusDetail(EE_PREFIX, 102, "system is experiencing some issue, try again later")

  val PUSH_NOTIF_FAILED                           = statusDetail(PN_PREFIX, 101, "sending push notification failed")

  val TAA_NOT_NEEDED                              = statusDetail(TAA_PREFIX, 101, "Transaction Author Agreement is not needed")
  val TAA_NOT_SET_ON_THE_LEDGER                   = statusDetail(TAA_PREFIX, 102, "Transaction Author Agreement is not set on the ledger")
  val TAA_INVALID_JSON                            = statusDetail(TAA_PREFIX, 103, "Transaction Author Agreement json is invalid")
  val TAA_REJECTED                                = statusDetail(TAA_PREFIX, 104, "Transaction Author Agreement sent with request was rejected by the ledger")
  val TAA_CONFIGURED_VERSION_INVALID              = statusDetail(TAA_PREFIX, 105, "Configured version of Transaction Author Agreement is invalid. Misconfigured or needs to be accepted/hashed again?")
  val TAA_CONFIGURATION_FOR_VERSION_NOT_FOUND     = statusDetail(TAA_PREFIX, 106, "Configuration for current Transaction Author Agreement not found. TAA not yet accepted?")
  val TAA_FAILED_TO_GET_CURRENT_VERSION           = statusDetail(TAA_PREFIX, 107, "Failed to get Transaction Author Agreement information from the ledger")
  val TAA_REQUIRED_BUT_DISABLED                   = statusDetail(TAA_PREFIX, 108, s"TAA is enabled on the ledger, but ${CommonConfig.LIB_INDY_LEDGER_TAA_ENABLED} is set to false in the configuration file")
  val TAA_NOT_REQUIRED_BUT_INCLUDED               = statusDetail(TAA_PREFIX, 109, "TAA is not enabled on the ledger, but was included in a write transaction")
  val TXN_UNKNOWN_REJECT_REASON                   = statusDetail(TAA_PREFIX, 111, "unknown reject reason")

  val URL_SHORTENING_FAILED                       = statusDetail(URL_PREFIX, 100, "Url shortening failed")

  //status code prefix constant
  object StatusCodePrefix {
    val GNR_PREFIX    = "GNR"       //general
    val SMS_PREFIX    = "SMS"       //sms/text
    val AIS_PREFIX    = "AKS"       //agency initialization
    val CS_PREFIX     = "CS"        //connection
    val ARS_PREFIX    = "ARS"       //agent registration
    val ACS_PREFIX    = "ACS"       //agent creation
    val CRS_PREFIX    = "CRS"       //connection request
    val MS_PREFIX     = "MS"        //message
    val VES_PREFIX    = "VES"       //validation error
    val MDS_PREFIX    = "MDS"       //message delivery
    val LVS_PREFIX    = "LVS"       //ledger validation
    val MCS_PREFIX    = "MCS"       //metrics capturing
    val EE_PREFIX     = "EE"        //event encryption
    val PN_PREFIX     = "PN"        //push notification
    val TAA_PREFIX    = "TAA"       //transaction author agreement
    val ISDK_PREFIX   = "ISDK"      //indy sdk
    val LPSS_PREFIX   = "LPSS"      //ledger pool service
    val AEPSS_PREFIX  = "AEPSS"     //actor event persistence service (dynamodb etc)
    val URL_PREFIX    = "URL"       //url
  }

  val allStatusDetails: List[StatusDetailEnum] = values.toList.map(convert)

  //note: assumption behind running this 'checkDuplicate()' function:
  // when developer (or ci/cd) will run any tests (unit/integration etc),
  // it will run some code for sure which would be using this `Status` object,
  // and as part of it's (Status object) initialization code, it will run `checkDuplicate()` function
  // which will check if there are any duplicate status codes defined and throw exception in that case
  checkDuplicate()

  def checkDuplicate(): Unit = {
    val duplicates = allStatusDetails.groupBy(_.statusCode).filter(_._2.size > 1).keySet
    if (duplicates.nonEmpty) {
      val err = "duplicate status code found (in Status.scala): " + duplicates
      logger.error("error: " + err)
      throw new InternalServerErrorException(UNHANDLED.statusCode, msgDetail = Option(err))
    }
  }

  def getFromCode(code: String): StatusDetail = {
    val matched = allStatusDetails.find(sd => sd.statusCode == code)
    val sde = matched.getOrElse {
      val errorMsg = s"status detail not found for code: $code"
      logger.error(errorMsg)
      throw new InternalServerErrorException(UNHANDLED.statusCode, msgDetail=Option(errorMsg))
    }
    sde.statusDetail
  }

  def getStatusMsgFromCode(code: String): String = getFromCode(code).statusMsg
  def getUnhandledError(e: Any): StatusDetail = UNHANDLED.copy(statusMsg=e.toString)
  def buildStatusCode(prefix: String, number: Int): String = s"$prefix-$number"
  def buildStatusDetail(statusCode: String, statusMsg: String): StatusDetail = {
    StatusDetailEnum(statusCode, statusMsg)    //temporary way to get rid of "Duplicate-id" issue
    StatusDetail(statusCode, statusMsg)
  }
  def statusDetail(category: String, number: Int, message: String): StatusDetail =
    buildStatusDetail(buildStatusCode(category, number), message)

  protected case class StatusDetailEnum(statusCode: String, statusMsg: String) extends super.Val {
    def statusDetail: StatusDetail = StatusDetail(statusCode, statusMsg)
  }
  case class StatusDetail(statusCode: String, statusMsg: String) {
    def withMessage(msg: String): StatusDetail = StatusDetail(statusCode, statusMsg = msg)
    def hasStatusCode(sc: String): Boolean = statusCode == sc
  }

  implicit def convert(value: Value): StatusDetailEnum = value.asInstanceOf[StatusDetailEnum]
}
