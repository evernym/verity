package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.actor.wallet.{CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, ProofCreated, ProofVerifResult}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.container.asyncapis.wallet.SchemaCreated
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.{AnonCredRequests, WalletAccess}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec

import java.util.UUID
import scala.util.{Failure, Success, Try}

abstract class PresentProofSpecBase
  extends TestsProtocolsImpl(PresentProofDef)
    with BasicFixtureSpec{

  val orgName = "Acme Corp"
  val logoUrl = "https://robohash.org/234"
  val agencyVerkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
  val publicDid = "UmTXHz4Kf4p8XHh5MiA4PK"

  def createTest1CredDef: String = "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1"

  val restriction1: RestrictionsV1 = RestrictionsV1(Some("NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0"),
    None,
    None,
    None,
    None,
    None
  )

  val requestedAttr1: ProofAttribute = ProofAttribute(
    Some("test"),
    None,
    Some(List(restriction1)),
    None,
    self_attest_allowed = false
  )

  val requestedAttr2: ProofAttribute = ProofAttribute(
    Some("test2"),
    None,
    Some(List(restriction1)),
    None,
    self_attest_allowed = false
  )

  val proposedAttr1Name: String = "pr-test1"
  val proposedAttr1: PresentationPreviewAttribute = PresentationPreviewAttribute(
    proposedAttr1Name,
    None,
    None,
    None,
    None
  )

  val proposedAttr2Name: String = "pr-test2"
  val proposedAttr2CredDef: String = "cred-def-pa2"
  val proposedAttr2: PresentationPreviewAttribute = PresentationPreviewAttribute(
    proposedAttr2Name,
    Some(proposedAttr2CredDef),
    None,
    None,
    None
  )

  val proposedPred1Name: String = "pr-pred1"
  val proposedPred1CredDef: String = "cred-def-pp1"
  val proposedPred1: PresentationPreviewPredicate = PresentationPreviewPredicate(
    proposedPred1Name,
    proposedPred1CredDef,
    ">",
    18
  )

  val selfAttest1: ProofAttribute = generateAttr(name=Some("attest1"), selfAttestedAllowed=true)

  val selfAttest2: ProofAttribute = generateAttr(name=Some("attest2"), selfAttestedAllowed=true)

  val invalidAttr: ProofAttribute = ProofAttribute(
    Some("test"),
    Some(List("test", "test2")),
    Some(List(restriction1)),
    None,
    self_attest_allowed = false
  )

  val reqWithPredicate: Ctl.Request = genReq(
    "",
    Some(List(selfAttest1, selfAttest2)),
    Some(List(ProofPredicate("a", "b", 2, None, None)))
  )

  val allSelfAttestProofReq: Ctl.Request = genReq("", Some(List(selfAttest1, selfAttest2)))

  val reqWithRestriction: Ctl.Request = genReq("", Some(List(requestedAttr1)))

  val reqWithSelfAttestAndRestrictions: Ctl.Request = genReq("", Some(List(selfAttest1, selfAttest2, requestedAttr1)))

  def assertRequestSentState(env: TestEnvir, numberOfReq: Int=1): States.RequestSent = {
    env.expectAs(state[States.RequestSent]){ s =>
      s.data.requests should not be empty
      s.data.requests should have size numberOfReq
      assertRequestUsedSegment(env, s.data.requests.head)
    }
  }

  def assertReviewRequestSig(env: TestEnvir): Sig.ReviewRequest = {
    env.expectAs(signal[Sig.ReviewRequest]) { _ => }
  }

  def assertRequestReceivedState(env: TestEnvir): States.RequestReceived = {
    env.expectAs(state[States.RequestReceived]){ s =>
      s.data.requests should not be empty
      s.data.requests should have size 1
      assertRequestUsedSegment(env, s.data.requests.head)
    }
  }

  def assertRequestUsedSegment(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[RequestUsed](segmentKey) {
      case Success(Some(r)) =>
        val rr = DefaultMsgCodec.fromJson[ProofRequest](r.requestRaw)
        rr.requested_attributes.values.toVector.contains(reqWithRestriction.proof_attrs.get.head)
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def assertCompleteState(env: TestEnvir,
                          verificationResult: Option[String]=Some(VerificationResults.ProofValidated)
                         ): States.Complete = {
    env.expectAs(state[States.Complete]) { s =>
      s.data.presentation should not be None
      s.data.verificationResults shouldBe verificationResult
      assertProofReceived(env, s.data.presentation.head)
    }
  }

  def assertProofReceived(env: TestEnvir, segmentKey: SegmentKey): Unit = {
    env.container_!.withSegment[PresentationGiven](segmentKey) {
      case Success(Some(r)) =>
        val p = DefaultMsgCodec.fromJson[ProofPresentation](r.presentation)
        assert(p.requested_proof
          .revealed_attrs
          .values
          .toVector
          .contains(RevealedAttr(0,"Alex", "99262857098057710338306967609588410025648622308394250666849665532448612202874")))
      case Success(None) => throw new Exception("No item")
      case Failure(e) => throw e
    }
  }

  def indyAccessMocks(f: FixtureParam, wa: WalletAccess=MockableWalletAccess.walletAccess(), la: LedgerAccess=MockableLedgerAccess()):
  (TestEnvir, TestEnvir) = {
    val (verifier, prover) = (f.alice, f.bob)

    verifier ledgerAccess la
    prover ledgerAccess la

    verifier walletAccess wa
    prover walletAccess wa

    (verifier, prover)
  }

  def generateAttr(name: Option[String]=None,
                   names: Option[List[String]]=None,
                   restrictions: Option[List[RestrictionsV1]]=None,
                   nonRevoked: Option[RevocationInterval] = None,
                   selfAttestedAllowed: Boolean = false): ProofAttribute =
    ProofAttribute(name, names, restrictions, nonRevoked, selfAttestedAllowed)

  def allowsAllSelfAttested(req: Ctl.Request): Boolean =
    ProofRequestUtil.requestToProofRequest(req).get.allowsAllSelfAttested

  def genReq(name: String,
             proof_attrs: Option[List[ProofAttribute]],
             proof_predicates: Option[List[ProofPredicate]]=None,
             revocation_interval: Option[RevocationInterval]=None): Ctl.Request =
    Ctl.Request(name, proof_attrs, proof_predicates, revocation_interval)

}

//MockableAnonCredRequests.basic
object PresentProofSpec {
  import com.evernym.verity.protocol.testkit.MockableAnonCredRequests.basic
  val invalidEncoding: AnonCredRequests = new AnonCredRequests {
    override def createSchema(issuerDID: DID,
                              name: String,
                              version: String,
                              data: String)
                             (handler: Try[SchemaCreated] => Unit): Unit =
      basic.createSchema(issuerDID, name, version, data)(handler)

    override def createCredDef(issuerDID: DID,
                               schemaJson: String,
                               tag: String,
                               sigType: Option[String],
                               revocationDetails: Option[String])
                              (handler: Try[CredDefCreated] => Unit): Unit =
      basic.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)(handler)

    override def createCredOffer(a1: String)(handler: Try[CredOfferCreated] => Unit): Unit = basic.createCredOffer(a1)(handler)
    override def createCredReq(a1: String, a2: DID, a3: String, a4: String)
                              (handler: Try[CredReqCreated] => Unit): Unit =
      basic.createCredReq(a1, a2, a3, a4)(handler)
    override def createCred(a1: String, a2: String, a3: String, a4: String, a5: Int)
                           (handler: Try[CredCreated] => Unit): Unit =
      basic.createCred(a1, a2, a3, a4, a5)(handler)
    override def credentialsForProofReq(a1: String)(handler: Try[CredForProofReqCreated] => Unit): Unit =
      basic.credentialsForProofReq(a1)(handler)
    override def verifyProof(a1: String, a2: String, a3: String, a4: String, a5: String, a6: String)
                            (handler: Try[ProofVerifResult] => Unit): Unit =
      basic.verifyProof(a1, a2, a3, a4, a5, a6)(handler)
    override def createProof(a1: String, a2: String, a3: String, a4: String, a5: String)
                            (handler: Try[ProofCreated] => Unit): Unit = handler(Try(
      // changes raw value of attr1_referent 'Alex' to 'Mark'
      ProofCreated(
        """{
          |   "proof":{},
          |   "requested_proof":{
          |      "revealed_attrs":{
          |         "attr1_referent":{
          |            "sub_proof_index":0,
          |            "raw":"Mark",
          |            "encoded":"99262857098057710338306967609588410025648622308394250666849665532448612202874"
          |         }
          |      },
          |      "self_attested_attrs":{
          |         "attr3_referent":"8-800-300"
          |      },
          |      "unrevealed_attrs":{
          |         "attr2_referent":{
          |            "sub_proof_index":0
          |         }
          |      },
          |      "predicates":{
          |         "predicate1_referent":{
          |            "sub_proof_index":0
          |         }
          |      }
          |   },
          |   "identifiers":[
          |      {
          |         "schema_id":"NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
          |         "cred_def_id":"NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
          |         "rev_reg_id":null,
          |         "timestamp":null
          |      }
          |   ]
          |}""".stripMargin
      )))

    override def storeCred(credId: String, credReqMetadataJson: String, credJson: String,
                           credDefJson: String, revRegDefJson: String)
                          (handler: Try[CredStored] => Unit): Unit =
      handler(Try(CredStored(Option(credId).getOrElse(UUID.randomUUID().toString))))
  }

  val invalidProof: AnonCredRequests = new AnonCredRequests {
    override def createSchema(issuerDID: DID,
                              name: String,
                              version: String,
                              data: String)
                             (handler: Try[SchemaCreated] => Unit): Unit =
      basic.createSchema(issuerDID, name, version, data)(handler)

    override def createCredDef(issuerDID: DID,
                               schemaJson: String,
                               tag: String,
                               sigType: Option[String],
                               revocationDetails: Option[String])
                              (handler: Try[CredDefCreated] => Unit): Unit =
      basic.createCredDef(issuerDID, schemaJson, tag, sigType, revocationDetails)(handler)

    override def createCredOffer(a1: String)(handler: Try[CredOfferCreated] => Unit): Unit = basic.createCredOffer(a1)(handler)
    override def createCredReq(a1: String, a2: DID, a3: String, a4: String)
                              (handler: Try[CredReqCreated] => Unit): Unit =
      basic.createCredReq(a1, a2, a3, a4)(handler)
    override def createCred(a1: String, a2: String, a3: String, a4: String, a5: Int)
                           (handler: Try[CredCreated] => Unit): Unit =
      basic.createCred(a1, a2, a3, a4, a5)(handler)
    override def credentialsForProofReq(a1: String)(handler: Try[CredForProofReqCreated] => Unit): Unit =
      basic.credentialsForProofReq(a1)(handler)
    override def verifyProof(a1: String, a2: String, a3: String, a4: String, a5: String, a6: String)
                            (handler: Try[ProofVerifResult] => Unit): Unit =
      handler(Try(ProofVerifResult(false)))
    override def createProof(a1: String, a2: String, a3: String, a4: String, a5: String)
                            (handler: Try[ProofCreated] => Unit): Unit =
      basic.createProof(a1, a2, a3, a4, a5)(handler)
    override def storeCred(credId: String, credDefJson: String, credReqMetadataJson: String, credJson: String,
                           revRegDefJson: String)
                          (handler: Try[CredStored] => Unit): Unit =
      basic.storeCred(credId, credDefJson, credReqMetadataJson, credJson, revRegDefJson)(handler)
  }
}
