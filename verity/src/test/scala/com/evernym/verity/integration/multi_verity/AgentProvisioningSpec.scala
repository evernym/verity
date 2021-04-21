//package com.evernym.verity.integration.multi_verity
//
//import com.evernym.verity.integration.PortProfile
//import com.evernym.verity.integration.multi_verity.base.{DomainProvider, VerityInstanceProvider}
//import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
//import com.evernym.verity.testkit.BasicSpec
//
//
//class AgentProvisioningSpec
//  extends VerityInstanceProvider
//    with DomainProvider
//    with BasicSpec {
//
//  lazy val verityVAS = setupVerity(PortProfile(9001, 2551, 8551), "11111111111111111111111111111111")
//  lazy val verityCAS = setupVerity(PortProfile(9002, 2552, 8552), "22222222222222222222222222222222")
//
//  lazy val issuerDomain = setupMyDomain(verityVAS)
//  lazy val holderDomain = setupMyDomain(verityCAS)
//
//  var invitation: Invitation = _
//
//  "IssuerDomain" - {
//
//    "when tried to fetch CAS agency agent keys" - {
//      "should be successful" in {
//        issuerDomain.fetchAgencyKey()
//        issuerDomain.agencyPublicDidOpt.isDefined shouldBe true
//      }
//    }
//
//    "when tried to provision verity agent" - {
//      "should be successful" in {
//        issuerDomain.provisionVerityEdgeAgent()
//      }
//    }
//
//    "when tried to register a webhook" - {
//      "should be successful" in {
//        issuerDomain.registerLocalAgentWebhook()
//      }
//    }
//
//    "when tried to update config" - {
//      "should be successful" in {
//        issuerDomain.updateConfig()
//      }
//    }
//
//  }
//
//  "HolderDomain" - {
//
//    "when tried to fetch VAS agency agent keys" - {
//      "should be successful" in {
//        holderDomain.fetchAgencyKey()
//        holderDomain.agencyPublicDidOpt.isDefined shouldBe true
//      }
//    }
//
//    "when tried to provision verity agent" - {
//      "should be successful" in {
//        holderDomain.provisionVerityCloudAgent()
//      }
//    }
//  }
//}
