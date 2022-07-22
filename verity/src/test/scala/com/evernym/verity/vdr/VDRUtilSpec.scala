package com.evernym.verity.vdr

import com.evernym.verity.testkit.BasicSpec

class VDRUtilSpec
  extends BasicSpec {

  val vdrUnqualifiedLedgerPrefixes: Seq[VdrDid] = List("did:indy:sovrin", "did:indy:sovrin:staging", "did:indy:sovrin:builder")
  val unqualifiedIssuerDid = "2wJPyULfLLnYTEFYzByfUR"
  val schemaName = "degree"
  val schemaVersion = "1.0"
  val schemaSeqNo = 10
  val credDefTag = "tag1"

  "VDRUtil" - {
    "when tried to extract information from fq did" - {
      "should be successful" in {
        vdrUnqualifiedLedgerPrefixes.foreach { vdrUnqualifiedLedgerPrefix =>
          val fqId = VDRUtil.toFqDID(unqualifiedIssuerDid, vdrUnqualifiedLedgerPrefix, Map("did:sov" -> "did:indy:sovrin"))
          VDRUtil.toFqDID(fqId, vdrUnqualifiedLedgerPrefix, Map("did:sov" -> "did:indy:sovrin")) shouldBe fqId
          fqId shouldBe s"$vdrUnqualifiedLedgerPrefix:$unqualifiedIssuerDid"
          VDRUtil.extractNamespace(Option(fqId), Option(vdrUnqualifiedLedgerPrefix)) shouldBe vdrUnqualifiedLedgerPrefix.replace("did:", "")
          VDRUtil.extractUnqualifiedDidStr(fqId) shouldBe unqualifiedIssuerDid
        }
      }
    }

    "when tried to extract information from fq schema id" - {
      "should be successful" in {
        vdrUnqualifiedLedgerPrefixes.foreach { vdrUnqualifiedLedgerPrefix =>
          List(s"$unqualifiedIssuerDid:2:$schemaName:$schemaVersion",
            s"$vdrUnqualifiedLedgerPrefix:$unqualifiedIssuerDid/anoncreds/v0/SCHEMA/$schemaName/$schemaVersion").foreach { schemaId =>
            val issuerFqDid = VDRUtil.toFqDID(unqualifiedIssuerDid, vdrUnqualifiedLedgerPrefix, Map("did:sov" -> "did:indy:sovrin"))
            val fqSchemaId = VDRUtil.toFqSchemaId_v0(schemaId, Option(issuerFqDid), Option(vdrUnqualifiedLedgerPrefix))
            VDRUtil.toFqSchemaId_v0(fqSchemaId, Option(issuerFqDid), Option(vdrUnqualifiedLedgerPrefix)) shouldBe fqSchemaId
            fqSchemaId shouldBe s"$vdrUnqualifiedLedgerPrefix:$unqualifiedIssuerDid/anoncreds/v0/SCHEMA/$schemaName/$schemaVersion"
            VDRUtil.extractNamespace(Option(fqSchemaId), Option(vdrUnqualifiedLedgerPrefix)) shouldBe vdrUnqualifiedLedgerPrefix.replace("did:", "")
          }
        }
      }
    }

    "when tried to extract information from fq cred def id" - {
      "should be successful" in {
        vdrUnqualifiedLedgerPrefixes.foreach { vdrUnqualifiedLedgerPrefix =>
          List(s"$unqualifiedIssuerDid:3:CL:$schemaSeqNo:$credDefTag",
            s"$vdrUnqualifiedLedgerPrefix:$unqualifiedIssuerDid/anoncreds/v0/CLAIM_DEF/$schemaSeqNo/$credDefTag").foreach { credDefId =>
            val issuerFqDid = VDRUtil.toFqDID(unqualifiedIssuerDid, vdrUnqualifiedLedgerPrefix, Map("did:sov" -> "did:indy:sovrin"))
            val fqCredDefId = VDRUtil.toFqCredDefId_v0(credDefId, Option(issuerFqDid), Option(vdrUnqualifiedLedgerPrefix))
            VDRUtil.toFqCredDefId_v0(fqCredDefId, Option(issuerFqDid), Option(vdrUnqualifiedLedgerPrefix)) shouldBe fqCredDefId
            fqCredDefId shouldBe s"$vdrUnqualifiedLedgerPrefix:$unqualifiedIssuerDid/anoncreds/v0/CLAIM_DEF/$schemaSeqNo/$credDefTag"
            VDRUtil.extractNamespace(Option(fqCredDefId), Option(vdrUnqualifiedLedgerPrefix)) shouldBe vdrUnqualifiedLedgerPrefix.replace("did:", "")
          }
        }
      }
    }

    "when tried to extract ledger prefix from fq DID" - {
      "should be successful" in {
        VDRUtil.extractLedgerPrefix(s"did:indy:sovrin:$unqualifiedIssuerDid") shouldBe "did:indy:sovrin"
        VDRUtil.extractLedgerPrefix(s"did:indy:sovrin:builder:$unqualifiedIssuerDid") shouldBe "did:indy:sovrin:builder"
        VDRUtil.extractLedgerPrefix(s"did:indy:sovrin:staging:$unqualifiedIssuerDid") shouldBe "did:indy:sovrin:staging"
      }
    }

    "when tried to convert id to legacy non qualified id" - {
      "should be successful" in {
        val legacyCredDefId1 = VDRUtil.toLegacyNonFqDid("did:indy:sovrin:builder:8aX4Hu3k7STufiKKxLtig7")
        legacyCredDefId1 shouldBe "8aX4Hu3k7STufiKKxLtig7"
        val legacyCredDefId2 = VDRUtil.toLegacyNonFqDid("8aX4Hu3k7STufiKKxLtig7")
        legacyCredDefId2 shouldBe "8aX4Hu3k7STufiKKxLtig7"
      }
    }

    "when tried to convert schemaId to legacy non qualified schema id" - {
      "should be successful" in {
        val legacyCredDefId1 = VDRUtil.toLegacyNonFqSchemaId("did:indy:sovrin:builder:8aX4Hu3k7STufiKKxLtig7/anoncreds/v0/SCHEMA/name/1.0")
        legacyCredDefId1 shouldBe "8aX4Hu3k7STufiKKxLtig7:2:name:1.0"
        val legacyCredDefId2 = VDRUtil.toLegacyNonFqSchemaId("8aX4Hu3k7STufiKKxLtig7:2:name:1.0")
        legacyCredDefId2 shouldBe "8aX4Hu3k7STufiKKxLtig7:2:name:1.0"
      }
    }

    "when tried to convert credDefId to legacy non qualified cred def id" - {
      "should be successful" in {
        //schema id as a sequence number
        val legacyCredDefId1 = VDRUtil.toLegacyNonFqCredDefId("did:indy:sovrin:builder:8aX4Hu3k7STufiKKxLtig7/anoncreds/v0/CLAIM_DEF/47951/latest")
        legacyCredDefId1 shouldBe "8aX4Hu3k7STufiKKxLtig7:3:CL:47951:latest"
        val legacyCredDefId2 = VDRUtil.toLegacyNonFqCredDefId("8aX4Hu3k7STufiKKxLtig7:3:CL:47951:latest")
        legacyCredDefId2 shouldBe "8aX4Hu3k7STufiKKxLtig7:3:CL:47951:latest"

        //schema id as a fully qualified identifier itself
        val schemaId = "did:indy:sovrin:builder:8aX4Hu3k7STufiKKxLtig7/anoncreds/v0/SCHEMA/name/1.0"
        val legacyCredDefId3 = VDRUtil.toLegacyNonFqCredDefId(s"did:indy:sovrin:builder:8aX4Hu3k7STufiKKxLtig7/anoncreds/v0/CLAIM_DEF/$schemaId/latest")
        legacyCredDefId3 shouldBe "8aX4Hu3k7STufiKKxLtig7:3:CL:8aX4Hu3k7STufiKKxLtig7:2:name:1.0:latest"
        val legacyCredDefId4 = VDRUtil.toLegacyNonFqCredDefId(s"8aX4Hu3k7STufiKKxLtig7:3:CL:8aX4Hu3k7STufiKKxLtig7:2:name:1.0:latest")
        legacyCredDefId4 shouldBe "8aX4Hu3k7STufiKKxLtig7:3:CL:8aX4Hu3k7STufiKKxLtig7:2:name:1.0:latest"
      }
    }
  }
}
