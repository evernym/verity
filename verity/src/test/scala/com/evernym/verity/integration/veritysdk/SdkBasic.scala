package com.evernym.verity.integration.veritysdk

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.sdk.utils.Context



class SdkBasic extends BasicSpec {
  "Verity SDK" - {
    "Should be callable" in { // Just to check that sdk is available
      classOf[Context].getSimpleName shouldBe "Context"
    }
  }
}
