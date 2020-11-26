package com.evernym.integrationtests.e2e.env

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EnvSpec extends AnyFreeSpec with Matchers {
  "LedgerConfig toString should work with non-test genesis files" in {
    val test = LedgerConfig("/home/user/dev/verity/target/genesis.txt", "Th7MpTaRZVRYnPiabds81Y", "000000000000000000000000Steward1", "STEWARD", 20, 60, 2)
    test.toString
  }

  "LedgerConfig toString should shorten test genesisfiles" in {
    val test = LedgerConfig("/home/devin/devel/verity/integration-tests/target/scalatest-runs/SdkFlowSpec-139024732401848481/genesis.txn", "Th7MpTaRZVRYnPiabds81Y", "000000000000000000000000Steward1", "STEWARD", 20, 60, 2)
    test.toString should include("../scalatest-runs/SdkFlowSpec-139024732401848481/genesis.txn")
  }
}
