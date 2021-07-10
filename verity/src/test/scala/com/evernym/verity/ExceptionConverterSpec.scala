package com.evernym.verity

import akka.http.scaladsl.model.{StatusCodes => HttpStatusCodes}
import akka.persistence.dynamodb.journal.{DynamoDBJournalFailure, DynamoDBJournalRejection}
import com.amazonaws.AmazonServiceException
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util.Util
import com.evernym.verity.util2.{ExceptionConverter, Status}
import org.mockito.scalatest.MockitoSugar

class ExceptionConverterSpec extends BasicSpec with MockitoSugar {
  val amazonCustomMsg = "testing Amazon service exception"
  val serviceName = "DynamoDB"
  val requestId = "testRequestId"
  val ase = new AmazonServiceException(amazonCustomMsg)
  ase.setErrorMessage(amazonCustomMsg)
  ase.setStatusCode(HttpStatusCodes.InternalServerError.intValue)
  ase.setRequestId(requestId)
  ase.setServiceName(serviceName)

  "when an exception is thrown" - {

    // Unhandled exception
    "when the exception is an unhandled exception" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing unhandled exception"
        val handledError = ExceptionConverter.getHandledError(new RuntimeException(customMsg))
        handledError shouldBe HandledErrorException(Status.UNHANDLED.statusCode,
          Option(Status.UNHANDLED.statusMsg), Option(Status.UNHANDLED.statusMsg), Option(customMsg))
      }
    }

    // DynamoDBJournalRejection
    "when the exception is a dynamodb journal rejection without a cause" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal rejection exception"
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalRejection(customMsg))
        handledError shouldBe HandledErrorException(Status.AEPSS_DYNAMODB_JOURNAL_REJECTION.statusCode, Option(customMsg))
      }
    }
    "when the exception is a dynamodb journal rejection with a cause that IS explicitly handled" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal rejection exception with explicitly handled cause"
        val errorCode = "ThrottlingException"
        ase.setErrorCode(errorCode)
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalRejection(customMsg, ase))
        handledError shouldBe HandledErrorException(Status.AEPSS_THROTTLING_EXCEPTION.statusCode,
          Option(Status.AEPSS_THROTTLING_EXCEPTION.statusMsg), Option(amazonCustomMsg),
          Option(s"$amazonCustomMsg (Service: $serviceName; Status Code: " +
            s"${HttpStatusCodes.InternalServerError.intValue}; Error Code: $errorCode; " +
            s"Request ID: $requestId)"))
      }
    }
    "when the exception is a dynamodb journal rejection with a cause that IS NOT explicitly handled" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal rejection exception with non-explicitly handled cause"
        val errorCode = "NonExplicitlyHandledException"
        ase.setErrorCode(errorCode)
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalRejection(customMsg, ase))
        handledError shouldBe HandledErrorException(errorCode, Option(amazonCustomMsg), None,
          Option(s"$amazonCustomMsg (Service: $serviceName; Status Code: " +
            s"${HttpStatusCodes.InternalServerError.intValue}; Error Code: $errorCode; " +
            s"Request ID: $requestId)"))
      }
    }

    // DynamoDBJournalFailure
    "when the exception is a dynamodb journal failure without a cause" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal failure exception"
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalFailure(customMsg))
        handledError shouldBe HandledErrorException(Status.AEPSS_DYNAMODB_JOURNAL_FAILURE.statusCode, Option(customMsg))
      }
    }
    "when the exception is a dynamodb journal failure with a cause that IS explicitly handled" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal failure exception with explicitly handled cause"
        val errorCode = "ThrottlingException"
        ase.setErrorCode(errorCode)
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalFailure(customMsg, ase))
        handledError shouldBe HandledErrorException(Status.AEPSS_THROTTLING_EXCEPTION.statusCode,
          Option(Status.AEPSS_THROTTLING_EXCEPTION.statusMsg), Option(amazonCustomMsg),
          Option(s"$amazonCustomMsg (Service: $serviceName; Status Code: " +
            s"${HttpStatusCodes.InternalServerError.intValue}; Error Code: $errorCode; " +
            s"Request ID: $requestId)"))
      }
    }
    "when the exception is a dynamodb journal failure with a cause that IS NOT explicitly handled" - {
      "should generate a HandledException with expected StatusDetail, error detail, and code" in {
        val customMsg = "testing dynamodb journal failure exception with non-explicitly handled cause"
        val errorCode = "NonExplicitlyHandledException"
        ase.setErrorCode(errorCode)
        val handledError = ExceptionConverter.getHandledError(new DynamoDBJournalFailure(customMsg, ase))
        handledError shouldBe HandledErrorException(errorCode, Option(amazonCustomMsg), None,
          Option(s"$amazonCustomMsg (Service: $serviceName; Status Code: " +
            s"${HttpStatusCodes.InternalServerError.intValue}; Error Code: $errorCode; " +
            s"Request ID: $requestId)"))
      }
    }
    "when dynamodb throws ServiceUnavailableException" - {
      "should generate exception without fail" in {
        Util.buildHandledError("unknown") shouldBe a[HandledErrorException]
      }
    }
  }
}
