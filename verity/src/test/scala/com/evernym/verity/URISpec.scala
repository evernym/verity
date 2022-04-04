package com.evernym.verity

import com.evernym.verity.testkit.BasicSpec

import java.net.URI


class URISpec
  extends BasicSpec {

  "An URI" - {
    "with non user info pattern" - {
      "should result into distinct components" in {
        val uri = new URI("event-source://v1:ssi:protocol/domainId/relationshipId/protocol/write-schema/0.6/pinstId123?threadId=threadId1#fragment")
        uri.getScheme shouldBe "event-source"
        uri.getSchemeSpecificPart shouldBe "//v1:ssi:protocol/domainId/relationshipId/protocol/write-schema/0.6/pinstId123?threadId=threadId1"
        uri.getAuthority shouldBe "v1:ssi:protocol"
        uri.getUserInfo shouldBe null
        uri.getHost shouldBe null
        uri.getPort shouldBe -1
        uri.getPath shouldBe "/domainId/relationshipId/protocol/write-schema/0.6/pinstId123"
        uri.getFragment shouldBe "fragment"
        uri.getQuery shouldBe "threadId=threadId1"
      }
    }
  }
}
