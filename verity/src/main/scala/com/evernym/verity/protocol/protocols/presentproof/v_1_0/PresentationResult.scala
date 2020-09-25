package com.evernym.verity.protocol.protocols.presentproof.v_1_0

object PresentationResults {
  def presentationToResults(presentation: ProofPresentation): AttributesPresented = {
    AttributesPresented(
      presentation.requested_proof.revealed_attrs.mapValues(x => RevealedAttributeInfo(x.sub_proof_index, x.raw)),
      presentation.requested_proof.self_attested_attrs,
      presentation.requested_proof.unrevealed_attrs.mapValues(x => SubProofReferent(x.sub_proof_index)),
      presentation.requested_proof.predicates.mapValues(x => SubProofReferent(x.sub_proof_index)),
      presentation.identifiers
    )
  }
}

case class AttributesPresented(revealed_attrs: Map[String, RevealedAttributeInfo],
                               self_attested_attrs: Map[String, String],
                               unrevealed_attrs: Map[String, SubProofReferent],
                               predicates: Map[String, SubProofReferent],
                               identifiers: Seq[Identifier])
case class RevealedAttributeInfo(identifier_index: Int, value: String)
case class SubProofReferent(identifier_index: Int)