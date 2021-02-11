package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.{Bucket_2_Legacy, Bucket_4, OneToOne, OneToOneDomain}
import com.evernym.verity.testkit.BasicSpec


class SegmentStoreStrategySpec extends BasicSpec {

  val pinstId = "pinst-id"
  val domainId = "domain-id"

  "OneToOne SegmentStoreStrategy" - {
    "can calculate SegmentId and SegmentAddress for given segment key" in {
      val segmentId = OneToOne.calcSegmentId("1a")
      segmentId shouldBe "0a238e1011c130f89921b3973f6e6ce0"
      OneToOne.calcSegmentAddress(pinstId, domainId, segmentId) shouldBe s"pinst-id-$segmentId"
    }
  }

  "Bucket_2_Legacy SegmentStoreStrategy" - {
    "can calculate SegmentId and SegmentAddress for given segment key" in {
      val segmentId = Bucket_2_Legacy.calcSegmentId("1a")
      segmentId shouldBe "16"
      Bucket_2_Legacy.calcSegmentAddress(pinstId, domainId, segmentId) shouldBe s"pinst-id-$segmentId"
    }
  }

  "Bucket_4 SegmentStoreStrategy" - {
    "can calculate SegmentId and SegmentAddress for given segment key" in {
      val segmentId = Bucket_4.calcSegmentId("1a")
      segmentId shouldBe "-9613"
      Bucket_2_Legacy.calcSegmentAddress(pinstId, domainId, segmentId) shouldBe s"pinst-id-$segmentId"
    }
  }

  "OneToOneDomain SegmentStoreStrategy" - {
    "can calculate SegmentId and SegmentAddress for given segment key" in {
      val segmentId = Bucket_4.calcSegmentId("1a")
      segmentId shouldBe "-9613"
      OneToOneDomain.calcSegmentAddress(pinstId, domainId, segmentId) shouldBe s"domain-id-$segmentId"
    }
  }
}

