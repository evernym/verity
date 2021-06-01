package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import java.util.UUID
import com.evernym.verity.actor.wallet.{CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, ProofCreated, ProofVerifResult}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.InitParamConstants.{AGENCY_DID_VER_KEY, DATA_RETENTION_POLICY, LOGO_URL, MY_PUBLIC_DID, NAME}
import com.evernym.verity.protocol.container.asyncapis.wallet.SchemaCreated
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.protocol.engine.asyncapi.wallet.{AnonCredRequests, WalletAccess}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableUrlShorteningAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import scala.util.{Failure, Success, Try}

class PresentProofSpec extends TestsProtocolsImpl(PresentProofDef)
  with BasicFixtureSpec {
    import PresentProofSpec._

    val orgName = "Acme Corp"
    val logoUrl = "https://robohash.org/234"
    val agencyVerkey = "87shCEvKAWw6JncoirStGxkRptVriLeNXytw9iRxpzGY"
    val publicDid = "UmTXHz4Kf4p8XHh5MiA4PK"

    override val defaultInitParams = Map(
      NAME -> orgName,
      LOGO_URL -> logoUrl,
      AGENCY_DID_VER_KEY -> agencyVerkey,
      MY_PUBLIC_DID -> publicDid,
      DATA_RETENTION_POLICY -> "100 days"
    )


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

  "Present Proof Protocol" - {
    "indy proof object" - {
      "should validate self attested values" in {_ =>
        // Passes with all self attested
        assert(allowsAllSelfAttested(allSelfAttestProofReq))

        // Fails with predicate
        assert(!allowsAllSelfAttested(reqWithPredicate))

        // Fails with both proof request restrictions and self attest
        assert(!allowsAllSelfAttested(reqWithSelfAttestAndRestrictions))

        // Fails with only proof request restrictions
        assert(!allowsAllSelfAttested(reqWithRestriction))
      }
     }

    "verifier start protocol" - {
      "should handle happy path" in { f =>

        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover

        assertRequestReceivedState(prover)

        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      "should handle Out-Of-Band Invitation happy path" in { f =>
        val (verifier, prover) = indyAccessMocks(f)

        verifier urlShortening MockableUrlShorteningAccess.shortened
        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None,
          Some(true)
        )

        // successful shortening
        val invitation = verifier expect signal[Sig.Invitation]
        invitation.shortInviteURL shouldBe Some("http://short.url")

        invitation.inviteURL should not be empty
        val base64 = invitation.inviteURL.split("oob=")(1)
        val invite = new String(Base64Util.getBase64UrlDecoded(base64))
        val inviteObj = new JSONObject(invite)

        inviteObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation") or be ("https://didcomm.org/out-of-band/1.0/invitation"))

        inviteObj.has("@id") shouldBe true
        InviteUtil.isThreadedInviteId(inviteObj.getString("@id"))
        val threadedInviteId = InviteUtil.parseThreadedInviteId(
          inviteObj.getString("@id")
        ).get
        threadedInviteId.protoRefStr shouldBe protoDef.msgFamily.protoRef.toString
        threadedInviteId.relationshipId shouldBe verifier.did_!
        threadedInviteId.threadId shouldBe verifier.currentInteraction.get.threadId.get



        inviteObj.getString("profileUrl") shouldBe logoUrl
        inviteObj.getString("label") shouldBe orgName
        inviteObj.getString("public_did") should endWith(publicDid)

        inviteObj.getJSONArray("service")
          .getJSONObject(0)
          .getJSONArray("routingKeys")
          .getString(1) shouldBe agencyVerkey

        val attachmentBase64 = inviteObj
          .getJSONArray("request~attach")
          .getJSONObject(0)
          .getJSONObject("data")
          .getString("base64")

        val attachment = new String(Base64Util.getBase64Decoded(attachmentBase64))
        val attachmentObj = new JSONObject(attachment)

        attachmentObj.getString("@id") should not be empty
        attachmentObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request-presentation") or be ("https://didcomm.org/present-proof/1.0/request-presentation"))
        attachmentObj.getJSONObject("~thread").getString("thid") should not be empty

        val attachedRequest: RequestPresentation = DefaultMsgCodec.fromJson[RequestPresentation](attachment)

        verifier.backState.roster.selfRole_! shouldBe Role.Verifier
        assertRequestSentState(verifier)

        prover ~ Ctl.AttachedRequest(attachedRequest)

        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)
        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      "should handle Out-Of-Band Invitation happy path without public did" in { f =>
        val (verifier, prover) = indyAccessMocks(f)

        verifier.initParams(defaultInitParams.updated(MY_PUBLIC_DID, ""))

        verifier urlShortening MockableUrlShorteningAccess.shortened

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None,
          Some(true)
        )

        // successful shortening
        val invitation = verifier expect signal[Sig.Invitation]
        invitation.shortInviteURL shouldBe Some("http://short.url")

        invitation.inviteURL should not be empty
        val base64 = invitation.inviteURL.split("oob=")(1)
        val invite = new String(Base64Util.getBase64UrlDecoded(base64))
        val inviteObj = new JSONObject(invite)

        inviteObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation") or be ("https://didcomm.org/out-of-band/1.0/invitation"))

        inviteObj.has("@id") shouldBe true
        InviteUtil.isThreadedInviteId(inviteObj.getString("@id"))
        val threadedInviteId = InviteUtil.parseThreadedInviteId(
          inviteObj.getString("@id")
        ).get
        threadedInviteId.protoRefStr shouldBe protoDef.msgFamily.protoRef.toString
        threadedInviteId.relationshipId shouldBe verifier.did_!
        threadedInviteId.threadId shouldBe verifier.currentInteraction.get.threadId.get



        inviteObj.getString("profileUrl") shouldBe logoUrl
        inviteObj.getString("label") shouldBe orgName
        inviteObj.has("public_did") shouldBe false

        inviteObj.getJSONArray("service")
          .getJSONObject(0)
          .getJSONArray("routingKeys")
          .getString(1) shouldBe agencyVerkey

        val attachmentBase64 = inviteObj
          .getJSONArray("request~attach")
          .getJSONObject(0)
          .getJSONObject("data")
          .getString("base64")

        val attachment = new String(Base64Util.getBase64Decoded(attachmentBase64))
        val attachmentObj = new JSONObject(attachment)

        attachmentObj.getString("@id") should not be empty
        attachmentObj.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request-presentation") or be ("https://didcomm.org/present-proof/1.0/request-presentation"))
        attachmentObj.getJSONObject("~thread").getString("thid") should not be empty

        val attachedRequest: RequestPresentation = DefaultMsgCodec.fromJson[RequestPresentation](attachment)

        verifier.backState.roster.selfRole_! shouldBe Role.Verifier
        assertRequestSentState(verifier)

        prover ~ Ctl.AttachedRequest(attachedRequest)

        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)
        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      "should handle Out-Of-Band Invitation shortening failed path" in { f =>
        val (verifier, prover) = indyAccessMocks(f)

        verifier urlShortening MockableUrlShorteningAccess.shorteningFailed

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None,
          Some(true)
        )

        // failed shortening
        val problemReport = verifier expect signal[Sig.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.shorteningFailed

        verifier expect state[States.Rejected]

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.status shouldBe "Rejected"
          s.error shouldBe None
          s.results shouldBe None
        }
      }

      "should handle prover being able to negotiate (verifier accepts)" in { f =>

        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and propose different presentation
        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        prover ~ Ctl.Propose(
          None,
          Some(List(proposedPred1)),
          "Proposal1"
        )

        verifier.expectAs( signal[Sig.ReviewProposal]) { msg =>
          msg.attributes shouldBe List()
          msg.predicates shouldBe List(proposedPred1)
          msg.comment shouldBe "Proposal1"
        }

        verifier.expectAs(state[States.ProposalReceived]) { s =>
          s.data.requests should have size 1
          s.data.proposals should have size 1
        }

        verifier ~ Ctl.AcceptProposal(None, None)

        assertRequestSentState(verifier, numberOfReq=2)

        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          msg.proof_request.non_revoked shouldBe None
          msg.proof_request.requested_attributes should have size 0
          msg.proof_request.requested_predicates should have size 1
          msg.proof_request.requested_predicates(proposedPred1Name) shouldBe ProofPredicate(
            proposedPred1Name,
            proposedPred1.predicate,
            proposedPred1.threshold,
            Some(List(RestrictionsV1(None,None,None,None,None,Some(proposedPred1CredDef)))),
            None
          )
        }

        prover.expectAs(state[States.RequestReceived]) { s =>
          s.data.requests should have size 2
          s.data.proposals should have size 1
        }

        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      "should handle verifier being able to renegotiate" in { f =>

        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and propose different presentation
        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        prover ~ Ctl.Propose(
          Some(List(proposedAttr1, proposedAttr2)),
          Some(List(proposedPred1)),
          "Proposal1"
        )

        verifier.expectAs( signal[Sig.ReviewProposal]) { msg =>
          msg.attributes shouldBe List(proposedAttr1, proposedAttr2)
          msg.predicates shouldBe List(proposedPred1)
          msg.comment shouldBe "Proposal1"
        }

        verifier.expectAs(state[States.ProposalReceived]) { s =>
          s.data.requests should have size 1
          s.data.proposals should have size 1
        }

        verifier ~ Ctl.Request(
          "",
          Some(List(requestedAttr2)),
          None,
          None
        )

        assertRequestSentState(verifier, numberOfReq=2)

        // Prover should receive request and approve it
        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          msg.proof_request.non_revoked shouldBe None
          msg.proof_request.requested_attributes should have size 1
          msg.proof_request.requested_attributes(requestedAttr2.name.get) shouldBe requestedAttr2

          msg.proof_request.requested_predicates should have size 0
        }

        prover.expectAs(state[States.RequestReceived]) { s =>
          s.data.requests should have size 2
          s.data.proposals should have size 1
        }

        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      "should handle prover being able to start by proposing presentation" in { f =>

        val (verifier, prover) = indyAccessMocks(f)

        // Prover starts protocol
        (prover engage verifier) ~ Ctl.Propose(
          Some(List(proposedAttr1)),
          None,
          "Proposal1"
        )

        prover.role shouldBe Role.Prover
        prover.expectAs(state[States.ProposalSent]){ s =>
          s.data.proposals should have size 1
        }

        verifier.expectAs( signal[Sig.ReviewProposal]) { msg =>
          msg.attributes shouldBe List(proposedAttr1)
          msg.predicates shouldBe List()
          msg.comment shouldBe "Proposal1"
        }
        verifier.role shouldBe Role.Verifier

        verifier.expectAs(state[States.ProposalReceived]) { s =>
          s.data.requests should have size 0
          s.data.proposals should have size 1
        }

        verifier ~ Ctl.AcceptProposal(None, None)

        assertRequestSentState(verifier)

        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          msg.proof_request.non_revoked shouldBe None
          msg.proof_request.requested_attributes should have size 1
          msg.proof_request.requested_attributes(proposedAttr1Name) shouldBe ProofAttribute(
            Some(proposedAttr1Name),
            None,
            None,
            None,
            self_attest_allowed = true
          )
          msg.proof_request.requested_predicates should have size 0
        }

        prover.expectAs(state[States.RequestReceived]) { s =>
          s.data.requests should have size 1
          s.data.proposals should have size 1
        }

        prover ~ Ctl.AcceptRequest()

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      // ignored because InMemoryProtocolContainer does not handle wallet async behavior yet
      "should handle all self attested - real wallet access" ignore { f =>
        val (verifier, prover) = indyAccessMocks(f, wa=MockableWalletAccess.walletAccess())

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ allSelfAttestProofReq

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        var selfAttestOptions: List[String] = List()
        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          selfAttestOptions = msg.proof_request.selfAttestOptions
          assert(msg.proof_request.allowsAllSelfAttested)
          assert(selfAttestOptions.size == 2)
          msg.proof_request.nonce shouldBe nonce.value
        }

        prover.role shouldBe Role.Prover
        prover.expectAs(state[States.RequestReceived]){ s =>
          s.data.requests should not be empty
          s.data.requests should have size 1
        }
        val selfAttestedAttrs = selfAttestOptions.map(x => x -> "test data").toMap
        prover ~ Ctl.AcceptRequest(selfAttestedAttrs)

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        verifier.expectAs(state[States.Complete]) { s =>
          s.data.presentation should not be None
          s.data.verificationResults.value shouldBe VerificationResults.ProofValidated
        }

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }
      }

      // ignored because InMemoryProtocolContainer does not handle wallet async behavior yet
      "should fail with unexpected self attested" ignore {f =>
        val (verifier, prover) = indyAccessMocks(f, wa=MockableWalletAccess.walletAccess())

        // Verifier starts protocol
        (verifier engage prover) ~ reqWithRestriction

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          assert(!msg.proof_request.allowsAllSelfAttested)
        }

        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        prover ~ Ctl.AcceptRequest(Map("invalid self attest" -> "invalid"))

        // Verifier should receive presentation and verify it
        prover.expectAs( signal[Sig.ProblemReport]) { msg =>
          msg.description.bestDescription() shouldBe Some("Ledger assets unavailable -- No ledger identifiers were included with the Presentation")
        }
      }

      "should pass when prover provides credential when verifier allows self attested" in { f =>
        val (verifier, prover) = indyAccessMocks(f)

        // Verifier starts protocol
        (verifier engage prover) ~ reqWithSelfAttestAndRestrictions

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        assertReviewRequestSig(prover)

        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        // Possibility of self attested but uses mocked credentials instead
        prover ~ Ctl.AcceptRequest(Map.empty)

        // Verifier should receive presentation and verify it
        verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
          msg.verification_result shouldBe VerificationResults.ProofValidated
          msg.requested_presentation should not be null
        }

        assertCompleteState(verifier)

        // Prover should be in Presented state
        prover.expectAs(state[States.Presented]) { s =>
          s.data.presentation should not be None
          s.data.presentationAcknowledged shouldBe true
        }

        verifier ~ Ctl.Status()
        verifier.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results.value shouldBe an[PresentationResult]
          s.status shouldBe "Complete"
        }

        prover ~ Ctl.Status()
        prover.expectAs(signal[Sig.StatusReport]) { s =>
          s.error shouldBe None
          s.results shouldBe None
          s.status shouldBe "Presented"
        }

      }

      "should handle rejection by prover" in { f =>
        val (verifier, prover) = indyAccessMocks(f)

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )

        prover ~ Ctl.Reject(Some("Because I said so"))

        prover.expectAs(state[States.Rejected]) { s =>
          s.whoRejected shouldBe Role.Prover
          s.reasonGiven.value shouldBe "Because I said so"
        }

        verifier.expectAs(signal[Sig.ProblemReport]){ s =>
          s.resolveDescription should include ("Because I said so")
        }

        verifier.expectAs(state[States.Rejected]) { s =>
          s.whoRejected shouldBe Role.Prover
          s.reasonGiven.value shouldBe "Because I said so"
        }

      }

      "should handle error cases" - {
        "Ledger Assets are not available for verifier" in { f =>
          val (verifier, prover) = indyAccessMocks(f)

          verifier ledgerAccess MockableLedgerAccess(false)

          // Verifier starts protocol
          (verifier engage prover) ~ Ctl.Request(
            "",
            Some(List(requestedAttr1)),
            None,
            None
          )

          prover ~ Ctl.AcceptRequest()

          verifier.expectAs( signal[Sig.PresentationResult]) { msg =>
            msg.verification_result shouldBe VerificationResults.ProofUndefined
            msg.requested_presentation should not be null
          }

          assertCompleteState(verifier, Some(VerificationResults.ProofUndefined))

          prover.expectAs(state[States.Presented]) { s =>
            s.data.presentation should not be None
            // Even though ledger access is not available, the
            // presentation should be Acknowledged
            s.data.presentationAcknowledged shouldBe true
          }

        }

        "Correctness check fails" in { f =>
          val (verifier, prover) = indyAccessMocks(f)

          // This will return an new raw value for `name` attr
          prover walletAccess MockableWalletAccess(invalidEncoding)

          // Verifier starts protocol
          (verifier engage prover) ~ reqWithRestriction

          prover ~ Ctl.AcceptRequest()

          verifier.expectAs(state[States.Complete]) { s =>
            // the correctness check should catch the invalid encoding don't match the raw value
            s.data.verificationResults.value shouldBe VerificationResults.ProofInvalid
          }
        }

        "Proof verification fails" in { f =>
          val (verifier, prover) = indyAccessMocks(f)

          // All proofs verifications will return false
          verifier walletAccess MockableWalletAccess(invalidProof)

          // Verifier starts protocol
          (verifier engage prover) ~ reqWithRestriction

          prover ~ Ctl.AcceptRequest()

          verifier.expectAs(state[States.Complete]) { s =>
            // the verification of the proof is false
            s.data.verificationResults.value shouldBe VerificationResults.ProofInvalid
          }
        }

        "Invalid Request signals Problem Report" in { f =>
          val (verifier, prover) = indyAccessMocks(f)

          // Verifier starts protocol
          (verifier engage prover) ~ Ctl.Request(
            "",
            Some(List(invalidAttr)),
            None,
            None
          )

          verifier expect signal[Sig.ProblemReport]

        }
      }

      "should handle data retention error cases" - {
        "Verifier receives Presentation message when request sent is expired" - {
          "should send problem report message" in { f =>

            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )

            verifier.role shouldBe Role.Verifier
            val requestSent = assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            assertRequestReceivedState(prover)

            // simulating expire of request on verifier side.
            verifier.container_!.removeSegment(requestSent.data.requests.head)

            prover ~ Ctl.AcceptRequest()

            // Verifier should receive problem report
            verifier.expectAs(signal[Sig.ProblemReport]) { msg =>
              msg.description.code shouldBe ProblemReportCodes.segmentedRetrieveFailed
            }

            // verifier should stay in its state
            verifier expect state[States.RequestSent]

            // prover should receive problem report
            prover.expectAs(signal[Sig.ProblemReport]) { msg =>
              msg.description.code shouldBe ProblemReportCodes.rejection
            }

            // Prover should be in Presented state
            prover.expectAs(state[States.Rejected]) { s =>
              s.data.presentation should not be None
              s.data.presentationAcknowledged shouldBe false
            }
          }
        }
        "Prover uses AcceptRequest control message when request received is expired" - {
          "should send problem report" in { f =>

            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )

            verifier.role shouldBe Role.Verifier
            assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            val requestReceived = assertRequestReceivedState(prover)

            // simulating expire of request on prover side.
            prover.container_!.removeSegment(requestReceived.data.requests.head)

            prover ~ Ctl.AcceptRequest()

            prover.expectAs(signal[Sig.ProblemReport]) { msg =>
              msg.description.code shouldBe ProblemReportCodes.segmentedRetrieveFailed
            }

            verifier ~ Ctl.Status()
            verifier.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None
              s.status shouldBe "RequestSent"
            }

            prover ~ Ctl.Status()
            prover.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None
              s.status shouldBe "RequestReceived"
            }
          }
        }
        "Verifier uses AcceptProposal control message when received proposal is expired" - {
          "should send problem report" in { f =>
            val (verifier, prover) = indyAccessMocks(f)

            // Prover starts protocol
            (prover engage verifier) ~ Ctl.Propose(
              Some(List(proposedAttr1)),
              None,
              "Proposal1"
            )

            prover.role shouldBe Role.Prover
            prover.expectAs(state[States.ProposalSent]) { s =>
              s.data.proposals should have size 1
            }

            verifier.expectAs(signal[Sig.ReviewProposal]) { msg =>
              msg.attributes shouldBe List(proposedAttr1)
              msg.predicates shouldBe List()
              msg.comment shouldBe "Proposal1"
            }
            verifier.role shouldBe Role.Verifier

            val proposalReceived = verifier.expectAs(state[States.ProposalReceived]) { s =>
              s.data.requests should have size 0
              s.data.proposals should have size 1
            }

            // simulating expire of proposal on verifier side.
            verifier.container_!.removeSegment(proposalReceived.data.proposals.head)

            verifier ~ Ctl.AcceptProposal(None, None)

            // Verifier should receive problem report
            verifier.expectAs(signal[Sig.ProblemReport]) { msg =>
              msg.description.code shouldBe ProblemReportCodes.segmentedRetrieveFailed
            }

            verifier ~ Ctl.Status()
            verifier.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None
              s.status shouldBe "ProposalReceived"
            }

            prover ~ Ctl.Status()
            prover.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None
              s.status shouldBe "ProposalSent"
            }
          }
        }

        "Verifier uses Status control message when received presentation is expired" - {
          "should get status without presentation data" in { f =>

            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )

            verifier.role shouldBe Role.Verifier
            assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            assertRequestReceivedState(prover)

            prover ~ Ctl.AcceptRequest()

            // Verifier should receive presentation and verify it
            verifier.expectAs(signal[Sig.PresentationResult]) { msg =>
              msg.verification_result shouldBe VerificationResults.ProofValidated
              msg.requested_presentation should not be null
            }

            val complete = assertCompleteState(verifier)

            // Prover should be in Presented state
            prover.expectAs(state[States.Presented]) { s =>
              s.data.presentation should not be None
              s.data.presentationAcknowledged shouldBe true
            }

            verifier ~ Ctl.Status()
            verifier.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results.value shouldBe an[PresentationResult]
              s.status shouldBe "Complete"
            }

            // simulating expire of presentation on verifier side.
            verifier.container_!.removeSegment(complete.data.presentation.get)

            // user should receive the state but without the presentation data.
            verifier ~ Ctl.Status()
            verifier.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None // presentation result is None (because it is expired).
              s.status shouldBe "Complete"
            }

            prover ~ Ctl.Status()
            prover.expectAs(signal[Sig.StatusReport]) { s =>
              s.error shouldBe None
              s.results shouldBe None
              s.status shouldBe "Presented"
            }
          }
        }
      }
    }
  }

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
