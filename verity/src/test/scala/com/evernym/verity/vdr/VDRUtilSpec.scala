package com.evernym.verity.vdr

import com.evernym.verity.testkit.BasicSpec

class VDRUtilSpec
  extends BasicSpec {

  val namespaces = List("sov", "indy:sovrin", "indy:sovrin:stage", "indy:sovrin:builder")

  "VDRUtil" - {
    "when tried to extract information from fq did" - {
      "should be successful" in {
        namespaces.foreach { namespace =>
          val fqId = VDRUtil.toFqDID("2wJPyULfLLnYTEFYzByfUR", namespace)
          VDRUtil.extractNamespace(Option(fqId), Option("sov")) shouldBe namespace
          VDRUtil.extractUnqualifiedDidStr(fqId) shouldBe "2wJPyULfLLnYTEFYzByfUR"
        }
      }
    }

    "when tried to extract information from fq schema id" - {
      "should be successful" in {
        namespaces.foreach { namespace =>
          val issuerFqDid = VDRUtil.toFqDID("2wJPyULfLLnYTEFYzByfUR", namespace)
          val fqSchemaId = VDRUtil.toFqSchemaId("2wJPyULfLLnYTEFYzByfUR:2:name:1.0", Option(issuerFqDid), Option("sov"))
          VDRUtil.extractNamespace(Option(fqSchemaId), Option("sov")) shouldBe namespace
        }
      }
    }

    "when tried to extract information from fq cred def id" - {
      "should be successful" in {
        namespaces.foreach { namespace =>
          val issuerFqDid = VDRUtil.toFqDID("2wJPyULfLLnYTEFYzByfUR", namespace)
          val fqCredDefId = VDRUtil.toFqCredDefId("2wJPyULfLLnYTEFYzByfUR:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1", Option(issuerFqDid), Option("sov"))
          VDRUtil.extractNamespace(Option(fqCredDefId), Option("sov")) shouldBe namespace
        }
      }
    }

    "when tried make fqDID" - {
      "should be successful" in {
        namespaces.foreach { namespace =>
          VDRUtil.toFqDID("2wJPyULfLLnYTEFYzByfUR", namespace) shouldBe s"did:$namespace:2wJPyULfLLnYTEFYzByfUR"
        }
      }
    }

    "when tried make fqSchemaId" - {
      "should be successful" in {
        namespaces.foreach { issuerNamespace =>
          val issuerDid = s"did:$issuerNamespace:2wJPyULfLLnYTEFYzByfUR"
          namespaces.foreach { legacyDefaultNamespace =>
            VDRUtil.toFqSchemaId("2wJPyULfLLnYTEFYzByfUR:2:name:1.0", Option(issuerDid), Option(legacyDefaultNamespace)) shouldBe s"schema:$issuerNamespace:did:$issuerNamespace:2wJPyULfLLnYTEFYzByfUR:2:name:1.0"
          }
        }
      }
    }

    "when tried make fqCredDefId" - {
      "should be successful" in {
        namespaces.foreach { issuerNamespace =>
          val issuerDid = s"did:$issuerNamespace:2wJPyULfLLnYTEFYzByfUR"
          namespaces.foreach { legacyDefaultNamespace =>
            VDRUtil.toFqCredDefId("2wJPyULfLLnYTEFYzByfUR:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1", Option(issuerDid), Option(legacyDefaultNamespace)) shouldBe s"creddef:$issuerNamespace:did:$issuerNamespace:2wJPyULfLLnYTEFYzByfUR:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"
          }
        }
      }
    }
  }
}
