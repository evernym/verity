package com.evernym.verity.protocol.testkit

import com.evernym.verity.actor.wallet.{CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, ProofCreated, ProofVerifResult}
import com.evernym.verity.protocol.container.asyncapis.wallet.SchemaCreated
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.AnonCredRequests

import scala.util.Try

object MockableAnonCredRequests {
  val basic = new AnonCredRequests {

    override def createSchema(issuerDID: DidStr,
                              name: String,
                              version: String,
                              data: String)
                             (handler: Try[SchemaCreated] => Unit): Unit = {
      val schemaId = "2hoqvcwupRTUNkXn6ArYzs:2:test-licence:4.4.4"
      val schemaJson =
        s"""
           {
             "ver":"1.0",
             "id":"2hoqvcwupRTUNkXn6ArYzs:2:test-licence:4.4.4",
             "name":"test-licence",
             "version":"4.4.4",
             "attrNames":["height","name","sex","age"],
             "seqNo":2471
           }
           """.stripMargin
      handler(Try(SchemaCreated(schemaId, schemaJson)))
    }

    override def createCredDef(issuerDID: DidStr,
                               schemaJson: String,
                               tag: String,
                               sigType: Option[String],
                               revocationDetails: Option[String])
                              (handler: Try[CredDefCreated] => Unit): Unit = {
      val cred_def_id = "2hoqvcwupRTUNkXn6ArYzs:3:CL:2471"
      val cred_def_json = """
        {
          "reqId":1523973501515533537,
          "identifier":"2hoqvcwupRTUNkXn6ArYzs",
          "operation":{
            "ref":1697,
            "data":{
              "primary":{"n":"1",
              "s":"2",
              "rms":"3",
              "r":{"age":"4","height":"5","name":"6","sex":"7"},
              "rctxt":"8",
               "z":"9"
              }
            },
            "type":"102",
            "signature_type":"CL"
          },
          "protocolVersion":1
        }
        """.stripMargin

      handler(Try(CredDefCreated(cred_def_id, cred_def_json)))
    }

    override def createCredOffer(credDefId: String)(handler: Try[CredOfferCreated] => Unit): Unit =
      handler(Try(
        CredOfferCreated(s"""
        {
        	"schema_id": "<schema-id>",
        	"cred_def_id": "$credDefId",
        	"nonce": "nonce",
        	"key_correctness_proof" : "<key_correctness_proof>"
        }""".stripMargin)))

    override def createCredReq(credDefId: String, proverDID: DidStr, credDefJson: String, credOfferJson: String)
                              (handler: Try[CredReqCreated] => Unit): Unit =
      handler(Try(CredReqCreated(
        s"""
        {
          "prover_did" : <prover-DID>,
          "cred_def_id" : $credDefId,
          "blinded_ms" : <blinded_master_secret>,
          "blinded_ms_correctness_proof" : <blinded_ms_correctness_proof>,
          "nonce": <nonce>
        }""".stripMargin,
        s"""
        {
          "master_secret_blinding_data":{"v_prime":"v_prime","vr_prime":null},
          "nonce":"nonce",
          "master_secret_name":"master_secret_name"
        }""".stripMargin
      )))

    override def createCred(credOfferJson: String, credReqJson: String, credValuesJson: String, revRegistryId: String, blobStorageReaderHandle: Int)
                           (handler: Try[CredCreated] => Unit): Unit = handler(Try(
      CredCreated(s"""
        {
          "schema_id": <schema_id>,
          "cred_def_id": <cred_def_id>,
          "values": <see cred_values_json above>,
          "signature": <signature>,
          "signature_correctness_proof": <signature_correctness_proof>
     }""".stripMargin)
    ))

    def storeCred(credId: String,
                  credReqMetadataJson: String,
                  credJson: String,
                  credDefJson: String,
                  revRegDefJson: String)
                 (handler: Try[CredStored] => Unit): Unit = handler(Try(
      CredStored(Option(credId).getOrElse("cred-id"))
    ))

    override def credentialsForProofReq(proofRequest: String)
                                       (handler: Try[CredForProofReqCreated] => Unit): Unit = handler(Try(
      CredForProofReqCreated(
      s"""{
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
    )))

    override def createProof(proofRequest: String, usedCredentials: String, schemas: String, credentialDefs: String, revStates: String)
                            (handler: Try[ProofCreated] => Unit): Unit = handler(Try(
      ProofCreated("""{"proof":{},"requested_proof":{"revealed_attrs":{"attr1_referent":{"sub_proof_index":0,"raw":"Alex","encoded":"99262857098057710338306967609588410025648622308394250666849665532448612202874"}},"self_attested_attrs":{"attr3_referent":"8-800-300"},"unrevealed_attrs":{"attr2_referent":{"sub_proof_index":0}},"predicates":{"predicate1_referent":{"sub_proof_index":0}}},"identifiers":[{"schema_id":"NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0","cred_def_id":"NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1","rev_reg_id":null,"timestamp":null}]}""")
    ))

    override def verifyProof(proofRequest: String, proof: String, schemas: String, credentialDefs: String, revocRegDefs: String, revocRegs: String)
                            (handler: Try[ProofVerifResult] => Unit): Unit = handler(Try(ProofVerifResult(true)))
  }

}