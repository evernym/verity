package com.evernym.verity.http.route_handlers.open.models


sealed trait RestResponse {
  def status: String
}

case class RestErrorResponse(errorCode: String, errorDetails: String, override val status: String = "Error") extends RestResponse

case class RestAcceptedResponse(override val status: String = "Accepted") extends RestResponse

case class RestOKResponse(result: Any, override val status: String = "OK") extends RestResponse
