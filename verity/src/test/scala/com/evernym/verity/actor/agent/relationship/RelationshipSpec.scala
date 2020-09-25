package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.RelationshipException.{MoreThanOneExistsException, MyDidNotSetException, RelationshipNotEmptyException, TheirDidNotSetException}
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.Parameters
import com.evernym.verity.testkit.BasicSpec


class RelationshipSpec extends BasicSpec {

  "Relationships" - {

    "come in five varieties" in {
      val r1 = NoRelationship
      val r2 = SelfRelationship()
      val r3 = PairwiseRelationship("some name")
      val r4 = NwiseRelationship("some group name")
      val r5 = AnywiseRelationship()
    }

    "No Relationship" - {
      val nr = NoRelationship

      "should honor Relationship api" in {
        nr.name shouldBe "no relationship"
        nr.myDidDoc shouldBe None
        nr.thoseDidDocs shouldBe Seq.empty
        nr.theirDidDoc shouldBe None

        nr.myDid shouldBe None
        intercept[MyDidNotSetException] {
          nr.myDid_!
        }
        intercept[MyDidNotSetException] {
          nr.myDidDoc_!
        }

        nr.theirDid shouldBe None
        intercept[TheirDidNotSetException] {
          nr.theirDid_!
        }
        intercept[TheirDidNotSetException] {
          nr.theirDidDoc_!
        }

        nr.isEmpty shouldBe true
        nr.nonEmpty shouldBe false
        intercept[RelationshipNotEmptyException] {
          nr.checkNotEmpty()
        }
      }

      "should handle init params" in {
        intercept[MyDidNotSetException] {
          nr.initParams(Set(SELF_ID))
        }
        intercept[TheirDidNotSetException] {
          nr.initParams(Set(OTHER_ID))
        }
      }
    }

    "Self Relationship" - {

      "when populated" - {
        val myDidDoc = DidDoc("mydid")
        val sr = SelfRelationship(myDidDoc)

        "should honor Relationship api" in {
          sr.name shouldBe "self"
          sr.myDidDoc shouldBe Some(myDidDoc)
          sr.thoseDidDocs shouldBe Seq.empty
          sr.theirDidDoc shouldBe None

          sr.myDid shouldBe Some("mydid")
          sr.myDid_! shouldBe "mydid"
          sr.myDidDoc_! shouldBe myDidDoc

          sr.theirDid shouldBe None
          intercept[TheirDidNotSetException] {
            sr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            sr.theirDidDoc_!
          }

          sr.isEmpty shouldBe false
          sr.nonEmpty shouldBe true
          sr.checkNotEmpty()
        }

        "should handle init params" in {
          sr.initParams(Set(SELF_ID)) shouldBe Parameters((SELF_ID, myDidDoc.did))
          intercept[TheirDidNotSetException] {
            sr.initParams(Set(OTHER_ID))
          }
        }
      }

      "when empty" - {
        val sr = SelfRelationship()

        "should honor Relationship api" in {

          sr.name shouldBe "self"
          sr.myDidDoc shouldBe None
          sr.thoseDidDocs shouldBe Seq.empty
          sr.theirDidDoc shouldBe None

          sr.myDid shouldBe None
          intercept[MyDidNotSetException] {
            sr.myDid_!
          }
          intercept[MyDidNotSetException] {
            sr.myDidDoc_!
          }

          sr.theirDid shouldBe None
          intercept[TheirDidNotSetException] {
            sr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            sr.theirDidDoc_!
          }

          sr.isEmpty shouldBe true
          sr.nonEmpty shouldBe false
          intercept[RelationshipNotEmptyException] {
            sr.checkNotEmpty()
          }
        }

        "should handle init params" in {
          intercept[MyDidNotSetException] {
            sr.initParams(Set(SELF_ID))
          }
          intercept[TheirDidNotSetException] {
            sr.initParams(Set(OTHER_ID))
          }
        }
      }
    }

    "Pairwise Relationship" - {
      "when populated" - {
        val pwr = PairwiseRelationship("Alice", "mydid", "herdid")

        "should honor Relationship api" in {

          val myDidDoc = DidDoc("mydid")
          val herDidDoc = DidDoc("herdid")
          pwr.name shouldBe "Alice"
          pwr.myDidDoc shouldBe Some(myDidDoc)
          pwr.thoseDidDocs shouldBe Seq(herDidDoc)
          pwr.theirDidDoc shouldBe Some(herDidDoc)

          pwr.myDid shouldBe Some("mydid")
          pwr.myDid_! shouldBe "mydid"
          pwr.myDidDoc_! shouldBe myDidDoc

          pwr.theirDid shouldBe Some("herdid")
          pwr.theirDid_! shouldBe "herdid"
          pwr.theirDidDoc_! shouldBe herDidDoc

          pwr.isEmpty shouldBe false
          pwr.nonEmpty shouldBe true
          pwr.checkNotEmpty()
        }
        "should handle init params" in {
          pwr.initParams(Set(SELF_ID)) shouldBe Parameters((SELF_ID, pwr.myDid_!))
          pwr.initParams(Set(OTHER_ID)) shouldBe Parameters((OTHER_ID, pwr.theirDid_!))
          pwr.initParams(Set(SELF_ID, OTHER_ID)) shouldBe Parameters((SELF_ID, pwr.myDid_!), (OTHER_ID, pwr.theirDid_!))
        }
      }

      "when empty" - {
        val pwr = PairwiseRelationship("Alice")

        "should honor Relationship api" in {

          pwr.name shouldBe "Alice"
          pwr.myDidDoc shouldBe None
          pwr.thoseDidDocs shouldBe Seq.empty
          pwr.theirDidDoc shouldBe None

          pwr.myDid shouldBe None
          intercept[MyDidNotSetException] {
            pwr.myDid_!
          }
          intercept[MyDidNotSetException] {
            pwr.myDidDoc_!
          }

          pwr.theirDid shouldBe None
          intercept[TheirDidNotSetException] {
            pwr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            pwr.theirDidDoc_!
          }

          pwr.isEmpty shouldBe true
          pwr.nonEmpty shouldBe false
          intercept[RelationshipNotEmptyException] {
            pwr.checkNotEmpty()
          }

        }
        "should handle init params" in {
          intercept[MyDidNotSetException] {
            pwr.initParams(Set(SELF_ID))
          }
          intercept[TheirDidNotSetException] {
            pwr.initParams(Set(OTHER_ID))
          }
        }
      }
    }

    "Anywise Relationship" - {
      "when populated" - {
        val myDidDoc = DidDoc("mydid")
        val pr = AnywiseRelationship(myDidDoc)

        "should honor Relationship api" in {

          pr.name shouldBe "anywise"
          pr.myDidDoc shouldBe Some(myDidDoc)
          pr.thoseDidDocs shouldBe Seq.empty
          pr.theirDidDoc shouldBe None

          pr.myDid shouldBe Some("mydid")
          pr.myDid_! shouldBe "mydid"
          pr.myDidDoc_! shouldBe myDidDoc

          intercept[TheirDidNotSetException] {
            pr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            pr.theirDidDoc_!
          }
          pr.isEmpty shouldBe false
          pr.nonEmpty shouldBe true
          pr.checkNotEmpty()
        }
        "should handle init params" in {
          pr.initParams(Set(SELF_ID)) shouldBe Parameters((SELF_ID, pr.myDid_!))
          intercept[TheirDidNotSetException] {
            pr.initParams(Set(OTHER_ID))
          }
        }
      }

      "when empty" - {
        val pr = AnywiseRelationship()

        "should honor Relationship api" in {
          pr.name shouldBe "anywise"
          pr.myDidDoc shouldBe None
          pr.thoseDidDocs shouldBe Seq.empty
          pr.theirDidDoc shouldBe None

          pr.myDid shouldBe None
          intercept[MyDidNotSetException] {
            pr.myDid_!
          }
          intercept[MyDidNotSetException] {
            pr.myDidDoc_!
          }

          intercept[TheirDidNotSetException] {
            pr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            pr.theirDidDoc_!
          }
          pr.isEmpty shouldBe true
          pr.nonEmpty shouldBe false
          intercept[RelationshipNotEmptyException] {
            pr.checkNotEmpty()
          }
        }
        "should handle init params" in {
          intercept[MyDidNotSetException] {
            pr.initParams(Set(SELF_ID))
          }
          intercept[TheirDidNotSetException] {
            pr.initParams(Set(OTHER_ID))
          }
        }
      }
    }

    "Nwise Relationship" - {
      "when populated" - {
        val myDidDoc = DidDoc("mydid")
        val bobDidDoc = DidDoc("bobdid")
        val carolDidDoc = DidDoc("caroldid")
        val gr = NwiseRelationship("platform", Option(myDidDoc), Seq(bobDidDoc, carolDidDoc))

        "should honor Relationship api" in {

          gr.name shouldBe "platform"
          gr.myDidDoc shouldBe Some(myDidDoc)
          gr.thoseDidDocs shouldBe Seq(bobDidDoc, carolDidDoc)
          intercept[MoreThanOneExistsException] {
            gr.theirDid_!
          }

          gr.myDid shouldBe Some("mydid")
          gr.myDid_! shouldBe "mydid"
          gr.myDidDoc_! shouldBe myDidDoc

          intercept[MoreThanOneExistsException] {
            gr.theirDid_!
          }
          intercept[MoreThanOneExistsException] {
            gr.theirDidDoc_!
          }

          gr.isEmpty shouldBe false
          gr.nonEmpty shouldBe true
          gr.checkNotEmpty()
        }
        "should handle init params" in {
          gr.initParams(Set(SELF_ID)) shouldBe Parameters((SELF_ID, gr.myDid_!))
          intercept[MoreThanOneExistsException] {
            gr.initParams(Set(OTHER_ID))
          }
        }
      }

      "when empty" - {
        val gr = NwiseRelationship("platform", None, Seq.empty)

        "should honor Relationship api" in {
          gr.name shouldBe "platform"
          gr.myDidDoc shouldBe None
          gr.thoseDidDocs shouldBe Seq.empty
          gr.theirDidDoc shouldBe None

          gr.myDid shouldBe None
          intercept[MyDidNotSetException] {
            gr.myDid_!
          }
          intercept[MyDidNotSetException] {
            gr.myDidDoc_!
          }

          intercept[TheirDidNotSetException] {
            gr.theirDid_!
          }
          intercept[TheirDidNotSetException] {
            gr.theirDidDoc_!
          }
          gr.isEmpty shouldBe true
          gr.nonEmpty shouldBe false
          intercept[RelationshipNotEmptyException] {
            gr.checkNotEmpty()
          }
        }
        "should handle init params" in {
          intercept[MyDidNotSetException] {
            gr.initParams(Set(SELF_ID))
          }
          intercept[TheirDidNotSetException] {
            gr.initParams(Set(OTHER_ID))
          }
        }
      }
    }
  }
}
