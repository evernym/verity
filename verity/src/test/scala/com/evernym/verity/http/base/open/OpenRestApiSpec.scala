package com.evernym.verity.http.base.open

import com.evernym.verity.http.base.EdgeEndpointBaseSpec

trait OpenRestApiSpec
  extends AgencyIdentitySpec { this : EdgeEndpointBaseSpec =>

  def testOpenRestApis(): Unit = {

    testAgencyIdentityAndDetails()

    testAriesInvitationDecoding()
  }
}
