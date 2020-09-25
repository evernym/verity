package com.evernym.verity.protocol.didcomm.conventions

import com.evernym.verity.testkit.BasicSpec


class CredValueEncoderV1_0Spec extends BasicSpec {

  "CredValueEncoder" - {

    "when asked to check string is a 32 bit int" - {
      "should provide correct answer" in {
        CredValueEncoderV1_0.is32BitInt("-2147483648") shouldBe true
        CredValueEncoderV1_0.is32BitInt("2147483647") shouldBe true
        CredValueEncoderV1_0.is32BitInt("87121") shouldBe true
        CredValueEncoderV1_0.is32BitInt("-2147483649") shouldBe false
        CredValueEncoderV1_0.is32BitInt("2147483648") shouldBe false
      }
    }

    "when asked to encode a 32 bit integer raw value" - {
      "should respond with same value" in {
        CredValueEncoderV1_0.encodedValue("-2147483648") shouldBe "-2147483648"
        CredValueEncoderV1_0.encodedValue("87121") shouldBe "87121"
        CredValueEncoderV1_0.encodedValue("2147483647") shouldBe "2147483647"
      }
    }

    "when asked to encode a string" - {
      "should respond with encoded value" in {
        CredValueEncoderV1_0.encodedValue("101 Wilson Lane") shouldBe
          "68086943237164982734333428280784300550565381723532936263016368251445461241953"

        CredValueEncoderV1_0.encodedValue("87121") shouldBe
          "87121"

        CredValueEncoderV1_0.encodedValue("SLC") shouldBe
          "101327353979588246869873249766058188995681113722618593621043638294296500696424"

        CredValueEncoderV1_0.encodedValue("101 Tela Lane") shouldBe
          "63690509275174663089934667471948380740244018358024875547775652380902762701972"

        CredValueEncoderV1_0.encodedValue("UT") shouldBe
          "93856629670657830351991220989031130499313559332549427637940645777813964461231"

        CredValueEncoderV1_0.encodedValue("") shouldBe
          "102987336249554097029535212322581322789799900648198034993379397001115665086549"

        CredValueEncoderV1_0.encodedValue(null) shouldBe
          "99769404535520360775991420569103450442789945655240760487761322098828903685777"

        CredValueEncoderV1_0.encodedValue(true) shouldBe
          "1"

        CredValueEncoderV1_0.encodedValue(false) shouldBe
          "0"

        CredValueEncoderV1_0.encodedValue("True") shouldBe
          "27471875274925838976481193902417661171675582237244292940724984695988062543640"

        CredValueEncoderV1_0.encodedValue("False") shouldBe
          "43710460381310391454089928988014746602980337898724813422905404670995938820350"

        CredValueEncoderV1_0.encodedValue("2147483647") shouldBe
          "2147483647"

        CredValueEncoderV1_0.encodedValue("2147483648") shouldBe
          "26221484005389514539852548961319751347124425277437769688639924217837557266135"

        CredValueEncoderV1_0.encodedValue("-2147483648") shouldBe
          "-2147483648"

        CredValueEncoderV1_0.encodedValue("-2147483649") shouldBe
          "68956915425095939579909400566452872085353864667122112803508671228696852865689"

        CredValueEncoderV1_0.encodedValue(2147483647) shouldBe
          "2147483647"

        CredValueEncoderV1_0.encodedValue(2147483648L) shouldBe
          "26221484005389514539852548961319751347124425277437769688639924217837557266135"

        CredValueEncoderV1_0.encodedValue(-2147483648) shouldBe
          "-2147483648"

        CredValueEncoderV1_0.encodedValue(0.0) shouldBe
          "62838607218564353630028473473939957328943626306458686867332534889076311281879"

        CredValueEncoderV1_0.encodedValue("0.0") shouldBe
          "62838607218564353630028473473939957328943626306458686867332534889076311281879"
      }
    }

  }

}
