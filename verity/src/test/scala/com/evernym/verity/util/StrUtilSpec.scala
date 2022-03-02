package com.evernym.verity.util

import com.evernym.verity.util.StrUtil._
import com.evernym.verity.testkit.{BasicSpec, Matchers}


class StrUtilSpec extends BasicSpec {

  "camelToKebab" in {
    camelToKebab(null) shouldBe ""
    camelToKebab("") shouldBe ""
    camelToKebab("StatusReport") shouldBe "status-report"
    camelToKebab("HITEST") shouldBe "h-i-t-e-s-t"
    camelToKebab("Take From Highest Shelf") shouldBe "take-from-highest-shelf"
    camelToKebab("testNew") shouldBe "test-new"
    camelToKebab("testNewAgain") shouldBe "test-new-again"
  }

  "camelToSeparate" in {
    camelToSeparateWords(null) shouldBe ""
    camelToSeparateWords("") shouldBe ""
    camelToSeparateWords("StatusReport") shouldBe "status report"
    camelToSeparateWords("HITEST") shouldBe "h i t e s t"
    camelToSeparateWords("Take From Highest Shelf") shouldBe "take from highest shelf"
    camelToSeparateWords("testNew") shouldBe "test new"
    camelToSeparateWords("testNewAgain") shouldBe "test new again"
  }

  "classToKebab" in {
    classToKebab[Matchers] shouldBe "matchers"
    classToKebab[StrUtilSpec] shouldBe "str-util-spec"
  }

  "camelToCapitalize" in {
    camelToCapitalize("testNew") shouldBe "Test New"
  }

}
