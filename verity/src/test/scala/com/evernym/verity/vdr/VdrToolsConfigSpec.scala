package com.evernym.verity.vdr

import com.evernym.verity.config.ConfigConstants.VDR_UNQUALIFIED_LEDGER_PREFIX
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TAAUtil
import com.evernym.verity.vdr.service.{IndyLedger, VDRToolsConfig}
import com.typesafe.config.ConfigFactory

import java.io.{File, FileNotFoundException}
import java.nio.file.Files
import java.time.LocalDate


class VdrToolsConfigSpec
  extends BasicSpec {

  def createGenesisFile(content: String): String = {
    val genesisTxn = File.createTempFile("genesis", ".txn")
    genesisTxn.deleteOnExit()
    Files.write(genesisTxn.toPath, content.getBytes)
    genesisTxn.getAbsolutePath
  }

  "VDRToolsConfig object" - {
    "when initialized with valid config" - {
      "should be successful" in {
        val genesisTxnFile = createGenesisFile("genesis txn data")

        val vdrToolsConfig = VDRToolsConfig.load(
          ConfigFactory.parseString(
            s"""
              |verity {
              |  vdr: {
              |    unqualified-ledger-prefix = "did:indy:sovrin"
              |    legacy-ledger-prefix-mappings {
              |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
              |    }
              |    ledgers = [
              |      {
              |        type: "indy"
              |        namespaces: ["indy:sovrin"]
              |        genesis-txn-file-location: "$genesisTxnFile"
              |        transaction-author-agreement: {
              |          version: "1"
              |          text: "lorem ipsum dolor"
              |          time-of-acceptance: ${TAAUtil.taaAcceptanceEpochDateTime(LocalDate.now().toString)}
              |          mechanism: "on_file"
              |        }
              |      }
              |    ]
              |  }
              |}
              |
              |""".stripMargin
          )
        )

        vdrToolsConfig.ledgers.length shouldBe 1

        val ledger = vdrToolsConfig.ledgers.head.asInstanceOf[IndyLedger]
        ledger.namespaces shouldBe List("indy:sovrin")
        ledger.genesisTxnData shouldBe "genesis txn data"
        ledger.taaConfig should not be empty

        val taa = ledger.taaConfig.get
        taa.getVersion shouldBe "1"
        taa.getText shouldBe "lorem ipsum dolor"
        taa.getAccMechType shouldBe "on_file"
        taa.getTime shouldBe expectedTimeOfAcceptance
      }
    }

    "when initialized without TAA config" - {
      "should be successful" in {
        val genesisTxnFile = createGenesisFile("genesis txn data")

        val vdrToolsConfig = VDRToolsConfig.load(
          ConfigFactory.parseString(
            s"""
               |verity {
               |  vdr: {
               |    unqualified-ledger-prefix = "did:indy:sovrin"
               |    legacy-ledger-prefix-mappings {
               |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
               |    }
               |    ledgers: [
               |      {
               |        type: "indy"
               |        namespaces: ["sov", "indy:sovrin"]
               |        genesis-txn-file-location: "$genesisTxnFile"
               |      }
               |    ]
               |  }
               |}
               |
               |""".stripMargin
          )
        )

        vdrToolsConfig.ledgers.length shouldBe 1

        val ledger = vdrToolsConfig.ledgers.head.asInstanceOf[IndyLedger]
        ledger.namespaces shouldBe List("sov", "indy:sovrin")
        ledger.genesisTxnData shouldBe "genesis txn data"
        ledger.taaConfig shouldBe None
      }
    }

    "when initialized with multiple ledgers" - {
      "should be successful" in {
        val genesisTxnFileA = createGenesisFile("some genesis txn data")
        val genesisTxnFileB = createGenesisFile("other genesis txn data")

        val vdrToolsConfig = VDRToolsConfig.load(
          ConfigFactory.parseString(
            s"""
               |verity {
               |  vdr: {
               |    unqualified-ledger-prefix = "did:indy:sovrin"
               |    legacy-ledger-prefix-mappings {
               |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
               |    }
               |    ledgers: [
               |      {
               |        type: "indy"
               |        namespaces: ["sov", "indy:sovrin"]
               |        genesis-txn-file-location: "$genesisTxnFileA"
               |      },
               |      {
               |        type: "indy"
               |        namespaces: ["indy:sovrin:builder"]
               |        genesis-txn-file-location: "$genesisTxnFileB"
               |      }
               |    ]
               |  }
               |}
               |
               |""".stripMargin
          )
        )

        vdrToolsConfig.ledgers.length shouldBe 2

        val ledgerA = vdrToolsConfig.ledgers.head.asInstanceOf[IndyLedger]
        ledgerA.namespaces shouldBe List("sov", "indy:sovrin")
        ledgerA.genesisTxnData shouldBe "some genesis txn data"
        ledgerA.taaConfig shouldBe None

        val ledgerB = vdrToolsConfig.ledgers(1).asInstanceOf[IndyLedger]
        ledgerB.namespaces shouldBe List("indy:sovrin:builder")
        ledgerB.genesisTxnData shouldBe "other genesis txn data"
        ledgerB.taaConfig shouldBe None
      }
    }

    "when initialized with empty ledger config" - {
      "should throw error" in {
        val ex = intercept[RuntimeException] {
          VDRToolsConfig.load(
            ConfigFactory.parseString(
              """
                |verity {
                |  vdr: {
                |    unqualified-ledger-prefix = "did:indy:sovrin"
                |    legacy-ledger-prefix-mappings {
                |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
                |    }
                |    ledgers: []
                |  }
                |}
                |
                |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe "[VDR] no ledger configs found"
      }
    }

    "when initialized with nonexistant file" - {
      "should throw error" in {
        val genesisTxn = java.io.File.createTempFile("genesis", ".txn")
        genesisTxn.delete()

        val ex = intercept[FileNotFoundException] {
          VDRToolsConfig.load(
            ConfigFactory.parseString(
              s"""
                 |verity {
                 |  vdr: {
                 |    unqualified-ledger-prefix = "did:indy:sovrin"
                 |    legacy-ledger-prefix-mappings {
                 |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
                 |    }
                 |    ledgers: [
                 |      {
                 |        type: "indy"
                 |        namespaces: ["sov", "indy:sovrin"]
                 |        genesis-txn-file-location: "${genesisTxn.getAbsolutePath}"
                 |      }
                 |    ]
                 |  }
                 |}
                 |
                 |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe s"${genesisTxn.getAbsolutePath} (No such file or directory)"
      }
    }

    "when initialized with duplicate namespaces" - {
      "should throw exception" in {
        val genesisTxnFileA = createGenesisFile("some genesis txn data")
        val genesisTxnFileB = createGenesisFile("other genesis txn data")

        val ex = intercept[RuntimeException] {
          VDRToolsConfig.load(
            ConfigFactory.parseString(
              s"""
                 |verity {
                 |  vdr: {
                 |    unqualified-ledger-prefix = "did:indy:sovrin"
                 |    legacy-ledger-prefix-mappings {
                 |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
                 |    }
                 |    ledgers: [
                 |      {
                 |        type: "indy"
                 |        namespaces: ["indy:sovrin", "indy:sovrin:stage"]
                 |        genesis-txn-file-location: "$genesisTxnFileA"
                 |      },
                 |      {
                 |        type: "indy"
                 |        namespaces: ["indy:sovrin:stage"]
                 |        genesis-txn-file-location: "$genesisTxnFileB"
                 |      }
                 |    ]
                 |  }
                 |}
                 |
                 |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe "[VDR] ledgers can not have shared namespaces"
      }
    }

    "when initialized without default namespaces" - {
      "should throw exception" in {

        val ex = intercept[RuntimeException] {
          VDRToolsConfig.load(
            ConfigFactory.parseString(
              s"""
                 |verity {
                 |  vdr: {
                 |    ledgers: [
                 |      {
                 |        type: "indy"
                 |        namespaces: ["sov", "indy:sovrin"]
                 |        genesis-txn-file-location: "/tmp/genesist.txt"
                 |      }
                 |    ]
                 |  }
                 |}
                 |
                 |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe "[VDR] required configuration not found: 'verity.vdr.unqualified-ledger-prefix'"
      }
    }

    "when initialized with default namespaces outside of registered ledger's namespaces" - {
      "should throw exception" in {
        val genesisTxnFile = createGenesisFile("some genesis txn data")
        val ex = intercept[RuntimeException] {
          VDRToolsConfig.load(
            ConfigFactory.parseString(
              s"""
                 |verity {
                 |  vdr: {
                 |    unqualified-ledger-prefix = "did:test"
                 |    legacy-ledger-prefix-mappings {
                 |      "did:sov": "did:indy:sovrin"    # the value is expected to not changed based on environments
                 |    }
                 |    ledgers: [
                 |      {
                 |        type: "indy"
                 |        namespaces: ["indy:sovrin"]
                 |        genesis-txn-file-location: "$genesisTxnFile"
                 |      }
                 |    ]
                 |  }
                 |}
                 |
                 |""".stripMargin
            )
          )
        }
        ex.getMessage shouldBe s"[VDR] '$VDR_UNQUALIFIED_LEDGER_PREFIX' namespace (test) is not found in registered ledger's namespaces (indy:sovrin)"
      }
    }
  }

  val expectedTimeOfAcceptance: Long = TAAUtil.taaAcceptanceEpochDateTime(LocalDate.now().toString)
}
