package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.IssuerSetupDefinition
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerDefinition
import com.evernym.verity.protocol.protocols.walletBackup.WalletBackupProtoDef
import com.evernym.verity.testkit.BasicSpec


class PinstIdResolutionSpec extends BasicSpec {
  // These test are more to check that the pinstId Resolver Algorithm don't change over time
  // It is important that the same inputs result in the same output even if it is not the expected output.
  // If it needs to change, create a new version of pinstId Resolver, use that new version instead of changing an old
  // version.
  "V0_1" - {
    "set pinstId resolution" - {
      "first" in {
        val pinstId = PinstIdResolution.DEPRECATED_V0_1.resolve(
          null, //Not used in this version
          "",  // Not used in this version
          None,
          Some("0"), //Most protocols don't have thread ids
          Some(""),
          Some("GgJ4953oWhNpv6nECPQRPP")
        )
        pinstId shouldBe "6886be7356d0307b636d4c1ca4c2a433"
      }
      "second" in {
        val pinstId = PinstIdResolution.DEPRECATED_V0_1.resolve(
          null, //Not used in this version
          "",  // Not used in this version
          None,
          Some("0"), //Most protocols don't have thread ids
          Some("e6904c0c-643a-4e0f-a03d-d90791376bb5"),
          Some("S7Ne8wV7T1XNRYpFwS2wgw")
        )
        pinstId shouldBe "d413b6742a6ae119840626ef679c52a8"
      }
      "for agent scoped protocols, it ignores provided thread id" in {
        //basically, if protocol scope is agent, it should ignore
        // provided thread id and use default thread id
        val pinstId1 = PinstIdResolution.DEPRECATED_V0_1.resolve(
          WalletBackupProtoDef,
          "",  // Not used in this version
          None,
          Some("0113"), //Most protocols don't have thread ids
          Some("e6904c0c-643a-4e0f-a03d-d90791376bb5"),
          Some("S7Ne8wV7T1XNRYpFwS2wgw")
        )

        val pinstId2 = PinstIdResolution.DEPRECATED_V0_1.resolve(
          WalletBackupProtoDef,
          "",  // Not used in this version
          None,
          Some("0344"), //Most protocols don't have thread ids
          Some("e6904c0c-643a-4e0f-a03d-d90791376bb5"),
          Some("S7Ne8wV7T1XNRYpFwS2wgw")
        )

        pinstId1 shouldBe pinstId2
      }
    }
  }
  "V0_2" - {
    "set pinstId resolution" - {
      "fist - Agent scope" in {
        val pinstId = PinstIdResolution.V0_2.resolve(
          IssuerSetupDefinition,
          "QqC9ohN8cLHJ1dCTdCsR9S",
          None,
          None,
          None,
          None
        )

        pinstId shouldBe "e56289057d2f9d9167b104e5f5198757"
      }
      // No protocol def with relationship scope, once we have one we will want
      // fill this out and not ignore it.
      "second - Relationship scope" ignore  {
        val pinstId = PinstIdResolution.V0_2.resolve(
          ???,
          "QqC9ohN8cLHJ1dCTdCsR9S",
          Some("UpMtbaLm6XhNJTanwZdWCp"),
          None,
          None,
          None
        )

        pinstId shouldBe ""
      }
      "third - AdHoc scope" in {
        val pinstId = PinstIdResolution.V0_2.resolve(
          QuestionAnswerDefinition,
          "QqC9ohN8cLHJ1dCTdCsR9S",
          Some("UpMtbaLm6XhNJTanwZdWCp"),
          Some("55172890-ab65-4948-ae29-302d5a634b56"),
          None,
          None
        )

        pinstId shouldBe "13b446aa42707da590b94b374cb63b0e"
      }
    }
  }
}
