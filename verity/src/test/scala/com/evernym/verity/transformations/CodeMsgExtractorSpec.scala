package com.evernym.verity.transformations

import com.evernym.verity.actor.KeyCreated
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.CodeMsgExtractor

class CodeMsgExtractorSpec extends BasicSpec {

  "CodeMsgExtractor" - {

    "when called 'pack' and 'unpack' functions with different length of type codes" - {
      "should respond appropriate data" in {
        //testing with different length of type codes
        val typeCodes = List(9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999)

        typeCodes.foreach { code =>
          val msg = KeyCreated("forDID").toByteArray
          val packedData = CodeMsgExtractor.pack(code, msg)
          val (extractedCode, extractedMsg) = CodeMsgExtractor.unpack(packedData)
          extractedCode shouldBe code
          extractedMsg shouldBe msg
        }
      }
    }
  }
}
