package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.Constants.UNKNOWN_OTHER_ID
import com.evernym.verity.constants.InitParamConstants.{OTHER_ID, SELF_ID}
import com.evernym.verity.protocol.engine.{Parameter, Parameters}
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.OptionValues

class PresentProofNonProtocolSpec extends BasicSpec with OptionValues {
  "Present Proof Protocol Definition" - {
    "should have two roles" in {
      PresentProofDef.roles.size shouldBe 2
      PresentProofDef.roles shouldBe Set(Role.Verifier, Role.Prover)
    }

    "should create correct Init control message" in {
      var testSet = Set(
        Parameter(SELF_ID, "1"),
        Parameter(OTHER_ID, UNKNOWN_OTHER_ID)
      )

      val message = PresentProofDef.createInitMsg(Parameters(testSet))
      message shouldBe an[Ctl.Init]
      val init = message.asInstanceOf[Ctl.Init]
      init.otherId shouldBe None
      init.selfId shouldBe "1"

      testSet = Set(
        Parameter(SELF_ID, "1"),
        Parameter(OTHER_ID, "2")
      )

      val message1 = PresentProofDef.createInitMsg(Parameters(testSet))
      message1 shouldBe an[Ctl.Init]
      val init2 = message1.asInstanceOf[Ctl.Init]
      init2.otherId.value shouldBe "2"
      init2.selfId shouldBe "1"

    }
  }

  "Proof Request Json" - {
    "case class produces correct JSON" in {
      val nonRevok = RevocationInterval(Some(1), Some(2))

      val restr = RestrictionsV1(
        Some("sdf"),
        Some("sdf"),
        Some("sdf"),
        Some("sdf"),
        Some("sdf"),
        Some("sdf")
      )

      val attr = ProofAttribute(
        Some(""),
        None,
        Some(List(restr)),
        Some(nonRevok),
        self_attest_allowed = false
      )

      val pred = ProofPredicate("sdf", "ge", 12, Some(List(restr)), Some(nonRevok))

      val pr = ProofRequest(
        "899939393",
        "Test",
        "1.0",
        Map("referent" -> attr),
        Map("referent2" -> pred),
        Some(nonRevok)
      )
      val json = DefaultMsgCodec.toJson(pr)
    }
  }

  "requestToProofRequest tests" in {
    val pr = ProofRequestUtil.requestToProofRequest(Ctl.Request("test", None, None, None))

  }

  "attrReferents test" in {

    val list = ProofRequestUtil.attrReferents(Seq(
      ProofAttribute(Some("name"), None, None, None, self_attest_allowed = false)
    )).keys

    list shouldBe Set("name")

    val list2 = ProofRequestUtil.attrReferents(Seq(
      ProofAttribute(Some("name"), None, None, None, self_attest_allowed = false),
      ProofAttribute(Some("lastname"), None, None, None, self_attest_allowed = false),
      ProofAttribute(Some("name"), None, None, None, self_attest_allowed = false)
    )).keys

    list2 shouldBe Set("name[1]", "lastname", "name[2]")

    val list3 = ProofRequestUtil.attrReferents(Seq(
      ProofAttribute(Some("name"), None, None, None, self_attest_allowed = false),
      ProofAttribute(Some("lastname"), None, None, None, self_attest_allowed = false),
      ProofAttribute(Some("name"), None, None, None, self_attest_allowed = false),
      ProofAttribute(None, Some(List("address", "from")), None, None, self_attest_allowed = false)
    )).keys

    list3 shouldBe Set("name[1]", "lastname", "name[2]", "address:from")

    intercept[Exception]{
      ProofRequestUtil.attrReferents(Seq(
        ProofAttribute(Some("name"), Some(List("address", "from")), None, None, self_attest_allowed = false)
      ))
    }

    intercept[Exception]{
      ProofRequestUtil.attrReferents(Seq(
        ProofAttribute(None, None, None, None, self_attest_allowed = false)
      ))
    }
  }

  "ProofRequest" in {
    val test = """{
                 |   "name":"proof_req_1",
                 |   "requested_attributes":{
                 |      "attr2_referent":{
                 |         "name":"sex"
                 |      },
                 |      "attr1_referent":{
                 |         "name":"name"
                 |      },
                 |      "attr3_referent":{
                 |         "name":"phone"
                 |      }
                 |   },
                 |   "nonce":"576983437197747054234864",
                 |   "version":"0.1",
                 |   "requested_predicates":{
                 |      "predicate1_referent":{
                 |         "p_value":18,
                 |         "name":"age",
                 |         "p_type":">="
                 |      }
                 |   }
                 |}""".stripMargin
    val result = DefaultMsgCodec.fromJson[ProofRequest](test)
    result
  }

  "AvailableCredentials" in {
    val test = """{
      |   "attrs":{
      |      "attr1_referent":[
      |         {
      |            "cred_info":{
      |               "referent":"27215870-7b83-4f5c-a4d2-b6f5950791f0",
      |               "attrs":{
      |                  "name":"Alex",
      |                  "age":"28",
      |                  "height":"175",
      |                  "sex":"male"
      |               },
      |               "schema_id":"NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
      |               "cred_def_id":"NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
      |               "rev_reg_id":null,
      |               "cred_rev_id":null
      |            },
      |            "interval":null
      |         }
      |      ],
      |      "attr3_referent":[
      |
      |      ],
      |      "attr2_referent":[
      |         {
      |            "cred_info":{
      |               "referent":"27215870-7b83-4f5c-a4d2-b6f5950791f0",
      |               "attrs":{
      |                  "sex":"male",
      |                  "height":"175",
      |                  "age":"28",
      |                  "name":"Alex"
      |               },
      |               "schema_id":"NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
      |               "cred_def_id":"NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
      |               "rev_reg_id":null,
      |               "cred_rev_id":null
      |            },
      |            "interval":null
      |         }
      |      ]
      |   },
      |   "predicates":{
      |      "predicate1_referent":[
      |         {
      |            "cred_info":{
      |               "referent":"27215870-7b83-4f5c-a4d2-b6f5950791f0",
      |               "attrs":{
      |                  "sex":"male",
      |                  "age":"28",
      |                  "height":"175",
      |                  "name":"Alex"
      |               },
      |               "schema_id":"NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
      |               "cred_def_id":"NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
      |               "rev_reg_id":null,
      |               "cred_rev_id":null
      |            },
      |            "interval":null
      |         }
      |      ]
      |   }
      |}""".stripMargin
    val result = DefaultMsgCodec.fromJson[AvailableCredentials](test)
    result.attrs shouldNot be (null)
    result.predicates shouldNot be (null)

  }
}
