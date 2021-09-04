package com.evernym.verity.protocol.protocols.presentproof.v_1_0.expire_after_days

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofSpec.{invalidEncoding, invalidProof}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{Role, _}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableUrlShorteningAccess, MockableWalletAccess}
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import org.json.JSONObject


class PresentProofSpec
  extends PresentProofSpecBase {

  override val defaultInitParams = Map(
    NAME -> orgName,
    LOGO_URL -> logoUrl,
    AGENCY_DID_VER_KEY -> agencyVerkey,
    MY_PUBLIC_DID -> publicDid,
    DATA_RETENTION_POLICY -> "100 days"
  )

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )
        f.checkTotalSegments(2)

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover

        assertRequestReceivedState(prover)

        prover ~ Ctl.AcceptRequest()
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(0)
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
        f.checkTotalSegments(1)

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
        threadedInviteId.protoRefStr shouldBe protoDef.protoRef.toString
        threadedInviteId.relationshipId shouldBe verifier.did_!
        threadedInviteId.threadId shouldBe verifier.currentInteraction.get.threadId.get

        inviteObj.getString("profileUrl") shouldBe logoUrl
        inviteObj.getString("label") shouldBe orgName
        inviteObj.getString("public_did") should endWith(publicDid)

        inviteObj.getJSONArray("service")
          .getJSONObject(0)
          .getJSONArray("routingKeys")
          .getString(1) should (be (agencyVerkey) or be (agencyDidkey))

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
        f.checkTotalSegments(2)

        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)
        prover ~ Ctl.AcceptRequest()
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(0)
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
        f.checkTotalSegments(1)

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
        threadedInviteId.protoRefStr shouldBe protoDef.protoRef.toString
        threadedInviteId.relationshipId shouldBe verifier.did_!
        threadedInviteId.threadId shouldBe verifier.currentInteraction.get.threadId.get

        inviteObj.getString("profileUrl") shouldBe logoUrl
        inviteObj.getString("label") shouldBe orgName
        inviteObj.has("public_did") shouldBe false

        inviteObj.getJSONArray("service")
          .getJSONObject(0)
          .getJSONArray("routingKeys")
          .getString(1) should (be (agencyVerkey) or be (agencyDidkey))

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
        f.checkTotalSegments(2)

        assertReviewRequestSig(prover)
        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)
        prover ~ Ctl.AcceptRequest()
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(0)
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
        f.checkTotalSegments(1)

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )
        f.checkTotalSegments(2)
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
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(6)
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
        f.checkTotalSegments(8)

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )
        f.checkTotalSegments(2)

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
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(6)

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
        f.checkTotalSegments(8)

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        // Prover starts protocol
        (prover engage verifier) ~ Ctl.Propose(
          Some(List(proposedAttr1)),
          None,
          "Proposal1"
        )
        f.checkTotalSegments(2)

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
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(6)

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f, wa=MockableWalletAccess.walletAccess())

        var nonce: Option[Nonce] = None

        // Verifier starts protocol
        (verifier engage prover) ~ allSelfAttestProofReq
        f.checkTotalSegments(2)

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
        f.checkTotalSegments(4)

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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f, wa=MockableWalletAccess.walletAccess())

        // Verifier starts protocol
        (verifier engage prover) ~ reqWithRestriction
        f.checkTotalSegments(2)

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        prover.expectAs(signal[Sig.ReviewRequest]) { msg =>
          assert(!msg.proof_request.allowsAllSelfAttested)
        }

        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        prover ~ Ctl.AcceptRequest(Map("invalid self attest" -> "invalid"))
        f.checkTotalSegments(4)

        // Verifier should receive presentation and verify it
        prover.expectAs( signal[Sig.ProblemReport]) { msg =>
          msg.description.bestDescription() shouldBe Some("Ledger assets unavailable -- No ledger identifiers were included with the Presentation")
        }
      }

      "should pass when prover provides credential when verifier allows self attested" in { f =>
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        // Verifier starts protocol
        (verifier engage prover) ~ reqWithSelfAttestAndRestrictions
        f.checkTotalSegments(2)

        verifier.role shouldBe Role.Verifier
        assertRequestSentState(verifier)

        // Prover should receive request and approve it
        assertReviewRequestSig(prover)

        prover.role shouldBe Role.Prover
        assertRequestReceivedState(prover)

        // Possibility of self attested but uses mocked credentials instead
        prover ~ Ctl.AcceptRequest(Map.empty)
        f.checkTotalSegments(4)
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
        f.checkTotalSegments(0)
        val (verifier, prover) = indyAccessMocks(f)

        // Verifier starts protocol
        (verifier engage prover) ~ Ctl.Request(
          "",
          Some(List(requestedAttr1)),
          None,
          None
        )
        f.checkTotalSegments(2)

        prover ~ Ctl.Reject(Some("Because I said so"))
        f.checkTotalSegments(2)

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
          f.checkTotalSegments(0)
          val (verifier, prover) = indyAccessMocks(f)

          verifier ledgerAccess MockableLedgerAccess(false)

          // Verifier starts protocol
          (verifier engage prover) ~ Ctl.Request(
            "",
            Some(List(requestedAttr1)),
            None,
            None
          )
          f.checkTotalSegments(2)

          prover ~ Ctl.AcceptRequest()
          f.checkTotalSegments(4)

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
          f.checkTotalSegments(0)
          val (verifier, prover) = indyAccessMocks(f)

          // This will return an new raw value for `name` attr
          prover walletAccess MockableWalletAccess(invalidEncoding)

          // Verifier starts protocol
          (verifier engage prover) ~ reqWithRestriction
          f.checkTotalSegments(2)

          prover ~ Ctl.AcceptRequest()
          f.checkTotalSegments(4)

          verifier.expectAs(state[States.Complete]) { s =>
            // the correctness check should catch the invalid encoding don't match the raw value
            s.data.verificationResults.value shouldBe VerificationResults.ProofInvalid
          }
        }

        "Proof verification fails" in { f =>
          f.checkTotalSegments(0)
          val (verifier, prover) = indyAccessMocks(f)

          // All proofs verifications will return false
          verifier walletAccess MockableWalletAccess(invalidProof)

          // Verifier starts protocol
          (verifier engage prover) ~ reqWithRestriction
          f.checkTotalSegments(2)

          prover ~ Ctl.AcceptRequest()
          f.checkTotalSegments(4)

          verifier.expectAs(state[States.Complete]) { s =>
            // the verification of the proof is false
            s.data.verificationResults.value shouldBe VerificationResults.ProofInvalid
          }
        }

        "Invalid Request signals Problem Report" in { f =>
          f.checkTotalSegments(0)
          val (verifier, prover) = indyAccessMocks(f)

          // Verifier starts protocol
          (verifier engage prover) ~ Ctl.Request(
            "",
            Some(List(invalidAttr)),
            None,
            None
          )
          f.checkTotalSegments(0)

          verifier expect signal[Sig.ProblemReport]

        }
      }

      "should handle data retention error cases" - {
        "Verifier receives Presentation message when request sent is expired" - {
          "should send problem report message" in { f =>
            f.checkTotalSegments(0)
            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )
            f.checkTotalSegments(2)

            verifier.role shouldBe Role.Verifier
            val requestSent = assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            assertRequestReceivedState(prover)

            // simulating expire of request on verifier side.
            verifier.container_!.removeSegment(requestSent.data.requests.head)
            f.checkTotalSegments(1)

            prover ~ Ctl.AcceptRequest()
            f.checkTotalSegments(2)

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
            f.checkTotalSegments(0)
            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )
            f.checkTotalSegments(2)

            verifier.role shouldBe Role.Verifier
            assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            val requestReceived = assertRequestReceivedState(prover)

            // simulating expire of request on prover side.
            prover.container_!.removeSegment(requestReceived.data.requests.head)
            f.checkTotalSegments(1)

            prover ~ Ctl.AcceptRequest()
            f.checkTotalSegments(1)

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
            f.checkTotalSegments(0)
            val (verifier, prover) = indyAccessMocks(f)

            // Prover starts protocol
            (prover engage verifier) ~ Ctl.Propose(
              Some(List(proposedAttr1)),
              None,
              "Proposal1"
            )
            f.checkTotalSegments(2)

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
            f.checkTotalSegments(1)

            verifier ~ Ctl.AcceptProposal(None, None)
            f.checkTotalSegments(1)

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
            f.checkTotalSegments(0)
            val (verifier, prover) = indyAccessMocks(f)

            var nonce: Option[Nonce] = None

            // Verifier starts protocol
            (verifier engage prover) ~ Ctl.Request(
              "",
              Some(List(requestedAttr1)),
              None,
              None
            )
            f.checkTotalSegments(2)

            verifier.role shouldBe Role.Verifier
            assertRequestSentState(verifier)

            // Prover should receive request and approve it
            assertReviewRequestSig(prover)
            prover.role shouldBe Role.Prover

            assertRequestReceivedState(prover)

            prover ~ Ctl.AcceptRequest()
            f.checkTotalSegments(4)

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
            f.checkTotalSegments(3)

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
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}
