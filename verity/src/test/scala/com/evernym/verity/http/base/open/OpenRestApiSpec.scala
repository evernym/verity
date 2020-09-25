package com.evernym.verity.http.base.open

import com.evernym.verity.http.base.EndpointHandlerBaseSpec

trait OpenRestApiSpec
  extends AgencyIdentitySpec { this : EndpointHandlerBaseSpec =>

  def testOpenRestApis(): Unit = {

    testAgencyIdentityAndDetails()

    testAgentRestApiUsage()

    testAriesInvitationDecoding()
  }
}
