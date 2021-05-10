package com.evernym.verity

import akka.persistence.dynamodb.journal.{DynamoDBJournalFailure, DynamoDBJournalRejection}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.Util._
import com.evernym.verity.Exceptions._
import com.evernym.verity.Status._
import com.typesafe.scalalogging.Logger


trait ExceptionConverterBase {

  val logger: Logger = getLoggerByClass(classOf[ExceptionConverterBase])

  protected def converter: PartialFunction[Throwable, HandledErrorException]

  def getConverter: PartialFunction[Throwable, HandledErrorException] = converter

  protected def buildHandledException(respCode: String, respMsg: Option[String], respDetail: Option[String],
                                      errorDetail: Option[Any]): HandledErrorException = {
    buildHandledError(respCode, respMsg, respDetail, errorDetail)
  }

  protected def buildHandledException(sd: StatusDetail, respDetail: Option[String],
                            errorDetail: Option[Any]): HandledErrorException = {
    buildHandledError(sd.statusCode, Option(sd.statusMsg), respDetail, errorDetail)
  }

  protected def buildHandledException(ase: AmazonServiceException): HandledErrorException = {
    buildHandledException(ase.getErrorCode, Option(ase.getErrorMessage), None, Option(ase.getMessage))
  }

  protected def buildHandledException(respCode: String, ase: AmazonServiceException): HandledErrorException = {
    buildHandledException(respCode, Option(Status.getStatusMsgFromCode(respCode)), Option(ase.getErrorMessage),
      Option(ase.getMessage))
  }

  protected def buildHandledException(sd: StatusDetail, ase: AmazonServiceException): HandledErrorException = {
    buildHandledException(sd, Option(ase.getErrorMessage), Option(ase.getMessage))
  }

  protected def buildNoResponseFromService(ase: AmazonClientException): HandledErrorException = {
    buildHandledException(AEPSS_NO_RESPONSE_FROM_SERVICE, None, Option(ase.getMessage))
  }

  def getHandledError(e: Throwable): HandledErrorException = {
    if (converter.isDefinedAt(e)) converter(e)
    else if (converter.isDefinedAt(e.getCause)) converter(e.getCause)
    else {
      buildHandledException(UNHANDLED, Option(UNHANDLED.statusMsg), Option(Exceptions.getErrorMsg(e)))
    }
  }

}

private object DynamoDBExceptionConverter extends ExceptionConverterBase {

  override protected lazy val converter: PartialFunction[Throwable, HandledErrorException] = {
    case ase: AmazonServiceException =>
      //https://github.com/aws/aws-sdk-java/blob/master/aws-java-sdk-dynamodb/src/main/java/com/amazonaws/services/dynamodbv2/AmazonDynamoDBClient.java#L106
      ase.getErrorCode match {
        case "ItemCollectionSizeLimitExceededException" => buildHandledException(AEPSS_ITEM_COLLECTION_SIZE_LIMIT_EXCEEDED, ase)
        case "LimitExceededException"                   => buildHandledException(AEPSS_OPERATION_LIMIT_EXCEEDED, ase)
        case "ProvisionedThroughputExceededException"   => buildHandledException(AEPSS_TABLE_PROV_THROUGHPUT_EXCEEDED, ase)
        case "ThrottlingException"                      => buildHandledException(AEPSS_THROTTLING_EXCEPTION, ase)
        case "UnrecognizedClientException"              => buildHandledException(AEPSS_UNRECOGNIZED_CLIENT_EXCEPTION, ase)
        case "ResourceNotFoundException"                => buildHandledException(AEPSS_RESOURCE_NOT_FOUND, ase)
        case _                                          => buildHandledException(ase)
      }
    case ace: AmazonClientException => buildNoResponseFromService(ace)

    case ddjr: DynamoDBJournalRejection if ddjr.getCause == null =>
      logger.debug(s"DynamoDBJournalRejection: ${ddjr.getMessage}")
      buildHandledError(AEPSS_DYNAMODB_JOURNAL_REJECTION.withMessage(ddjr.getMessage))

    case ddjf: DynamoDBJournalFailure if ddjf.getCause == null =>
      logger.debug(s"DynamoDBJournalFailure: ${ddjf.getMessage}")
      buildHandledError(AEPSS_DYNAMODB_JOURNAL_FAILURE.withMessage(ddjf.getMessage))
  }
}

private object GeneralExceptionConverter extends ExceptionConverterBase {
  override protected lazy val converter: PartialFunction[Throwable, HandledErrorException] = {
    case e: HandledErrorException => e
  }
}

object ExceptionConverter extends ExceptionConverterBase {

  lazy val dbExceptionConverter: ExceptionConverterBase = DynamoDBExceptionConverter
  lazy val generalExceptionConverter: ExceptionConverterBase = GeneralExceptionConverter

  override protected lazy val converter: PartialFunction[Throwable, HandledErrorException] =
    dbExceptionConverter.getConverter orElse
      generalExceptionConverter.getConverter

}
