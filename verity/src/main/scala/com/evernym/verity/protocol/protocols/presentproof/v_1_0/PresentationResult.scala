package com.evernym.verity.protocol.protocols.presentproof.v_1_0

object PresentationResults {
  def presentationToResults(presentation: ProofPresentation): AttributesPresented = {
    AttributesPresented(
      {
        val revealedAttrs = presentation.requested_proof.revealed_attrs.view.mapValues(x => RevealedAttributeInfo(x.sub_proof_index, x.raw)).toMap
        val revealedGroupAttrs: Map[String, RevealedAttributeInfo] = presentation.requested_proof.revealed_attr_groups.getOrElse(Map.empty)
          .values.foldLeft(Map[String, RevealedAttributeInfo]()){(map, attrGroup) =>
          val values = attrGroup.values.view.mapValues(x => RevealedAttributeInfo(attrGroup.sub_proof_index, x.raw))
          map ++ values
        }
        revealedAttrs ++ revealedGroupAttrs
      },
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