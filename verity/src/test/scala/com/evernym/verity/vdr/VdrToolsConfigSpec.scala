package com.evernym.verity.vdr

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.vdr.service.{IndyLedger, VDRToolsConfig}
import com.typesafe.config.ConfigFactory

class VdrToolsConfigSpec
  extends BasicSpec {

  "VDRToolsConfig object" - {
    "when initialized with valid config" - {
      "should be successful" in {
        val vdrToolsConfig = VDRToolsConfig(
          ConfigFactory.parseString(
            """
              |verity {
              |
              |  vdr-tools {
              |    library-dir-location = "/usr/lib"
              |  }
              |
              |  vdrs: [
              |    {
              |      type: "indy-ledger"
              |      namespaces: ["indy:sovrin", "sov"]
              |      genesis-txn-file-location: "/path"
              |    }
              |  ]
              |}
              |
              |""".stripMargin
          )
        )
        vdrToolsConfig.libraryDirLocation shouldBe "/usr/lib"
        vdrToolsConfig.ledgers shouldBe List (
          IndyLedger(List("indy:sovrin", "sov"), "/path", None)
        )
      }
    }

    "when initialized with empty ledger config" - {
      "should throw error" in {
        val ex = intercept[RuntimeException] {
          VDRToolsConfig(
            ConfigFactory.parseString(
              """
                |verity {
                |
                |  vdr-tools {
                |    library-dir-location = "/usr/lib"
                |  }
                |
                |  vdrs: []
                |}
                |
                |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe "[VDR] no ledger configs found"
      }
    }
  }
}
