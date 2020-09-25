//package com.evernym.verity.protocol.protocols.didexchange.v_1_0
//
//import com.evernym.verity.protocol.protocols.connections.v_1_0.Ctl._
//import com.evernym.verity.protocol.protocols.connections.v_1_0.Role.{Invitee, Inviter}
//import com.evernym.verity.protocol.testkit.DSL.signal
//import com.evernym.verity.protocol.testkit.InteractionType.OneParty
//import com.evernym.verity.protocol.testkit.{InteractionType, MockableWalletAccess, TestsProtocolsImpl}
//import org.scalatest.{Matchers, OptionValues, fixture}
//
//class DIDExchangeProtocolSpec extends TestsProtocolsImpl(DIDExchangeDefinition) with BasicFixtureSpec with OptionValues {
//
//  override val defaultInteractionType: InteractionType = OneParty
//
//  "The DIDExchange Protocol" - {
//    "has two roles" in { _ =>
//      DIDExchangeDefinition.roles.size shouldBe 2
//    }
//
//    "and the roles are Inviter and Invitee" in { _ =>
//      DIDExchangeDefinition.roles shouldBe Set(Inviter, Invitee)
//    }
//  }
//
//  "Invitor sending prepare with DID leads to" - {
//    implicit val system = new TestSystem()
//
//    val inviter = setup("inviter")
//
//    "protocol transitioning to prepared state" in { s =>
//      inviter ~ PrepareWithDID("test-label", "not a DID. Fix me!!!")
//      inviter.state shouldBe a[State.Prepared]
//    }
//
//    "role changing to inviter" in { s =>
//      inviter.selfRole shouldBe Role.Inviter
//    }
//
//    "should get a signal" in { s =>
//      inviter expect signal [Signal.Prepared]
//    }
//  }
//
//  override val containerNames: Set[ContainerName] = Set("Inviter")
//
//  "Invitor sending prepare with key leads to" - {
//    implicit val system = new TestSystem()
//
//    val inviter = setup("inviter")
//
//    "protocol transitioning to prepared state" in { s =>
//      inviter ~ PrepareWithKey("test-label", Vector("some recipient key. Fix me!!!"), Vector("some routing key. Fix me!!!"))
//      inviter.state shouldBe a[State.Prepared]
//    }
//
//    "role changing to inviter" in { s =>
//      inviter.selfRole shouldBe Role.Inviter
//    }
//
//    "should get a signal" in { s=>
//      inviter expect signal [Signal.Prepared]
//    }
//  }
//
//  "Inviter updating protocol post sending invite with DID leads to" - {
//    implicit val system = new TestSystem()
//
//    val inviter = setup("inviter")
//
//    "protocol transitioning to invited state" in { s =>
//      inviter ~ PrepareWithDID("test-label", "not a DID. Fix me!!!")
//      inviter ~ InvitedWithDIDNotif()
//      inviter.state shouldBe a[State.Invited]
//    }
//  }
//
//  "Inviter updating protocol post sending invite with Key leads to" - {
//    implicit val system = new TestSystem()
//
//    val inviter = setup("inviter")
//
//    "protocol transitioning to invited state" in { s =>
//      inviter ~ PrepareWithKey("test-label", Vector("some recipient key. Fix me!!!"), Vector("some routing key. Fix me!!!"))
//      inviter ~ InvitedWithKeyNotif()
//      inviter.state shouldBe a[State.Invited]
//    }
//  }
//
//  "Invitee sending validate invitation command for DID invite" - {
//    implicit val system = new TestSystem()
//
//    val invitee = setup("invitee")
//
//    "role changing to invitee" in { s =>
//      invitee ~ ValidateInviteURL("http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJkaWQiOiJub3QgYSBESUQuIEZpeCBtZSEhISJ9")
//      invitee.selfRole shouldBe Role.Invitee
//    }
//
//    "protocol transitioning to invited state" in { s =>
//      invitee.state shouldBe a[State.Invited]
//    }
//
//    "should get a signal" in { s=>
//      invitee expect signal [Signal.ValidInviteWithDID]
//    }
//  }
//
//  "Invitee sending validate invitation command for Key invite" - {
//    implicit val system = new TestSystem()
//
//    val invitee = setup("invitee")
//
//    "role changing to invitee" in { s =>
//      invitee ~ ValidateInviteURL("http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJyZWNpcGllbnRLZXlzIjpbInNvbWUgcmVjaXBpZW50IGtleS4gRml4IG1lISEhIl0sInNlcnZpY2VFbmRwb2ludCI6Imludml0ZXIiLCJyb3V0aW5nS2V5cyI6WyJzb21lIHJvdXRpbmcga2V5LiBGaXggbWUhISEiXX0=")
//      invitee.selfRole shouldBe Role.Invitee
//    }
//
//    "protocol transitioning to invited state" in { s =>
//      invitee.state shouldBe a[State.Invited]
//    }
//
//    "should get a signal" in { s =>
//      invitee expect signal[Signal.ValidInviteWithKey]
//    }
//  }
//
//  "Invitee accepting invitation for Key invite" - {
//    implicit val system = new TestSystem()
//
//    val inviter = setup("inviter")
//    val invitee = setup("invitee")
//
//    "protocol transitioning to invited state" in { s =>
//      inviter ~ PrepareWithKey("test-label", Vector("some recipient key. Fix me!!!"), Vector("some routing key. Fix me!!!"))
//      inviter ~ InvitedWithKeyNotif()
//      inviter.state shouldBe a[State.Invited]
//      inviter.roster.participants.size shouldBe 1
//    }
//
//    invitee walletAccess MockableWalletAccess()
//
//    "state should change to accepted" in { s =>
//      invitee ~ ValidateInviteURL("http://example.com?c_i=eyJsYWJlbCI6InRlc3QtbGFiZWwiLCJyZWNpcGllbnRLZXlzIjpbInNvbWUgcmVjaXBpZW50IGtleS4gRml4IG1lISEhIl0sInNlcnZpY2VFbmRwb2ludCI6Imludml0ZXIiLCJyb3V0aW5nS2V5cyI6WyJzb21lIHJvdXRpbmcga2V5LiBGaXggbWUhISEiXX0=")
//      invitee ~ Accept("invitee")
//      invitee.state shouldBe a[State.Accepted]
//    }
//
//    "should get a signal for DID creation" in { s =>
//      invitee expect signal[Signal.ValidInviteWithKey]
//      invitee expect signal[Signal.DIDCreated]
//    }
//
//    inviter walletAccess MockableWalletAccess()
//
//    "should be able to send an exchange request and expect state transition to Requested" in { s =>
//      invitee ~ SendExchangeReq("test-label-2", Vector.empty)
//      invitee.state shouldBe a[State.ReqSent]
//    }
//
//    "should get a signal for exchange request being sent" in { s =>
//      invitee expect signal[Signal.ExchangeReqSent]
//    }
//
//    "inviter state should change to requested" in { s =>
//      inviter.state shouldBe a[State.ReqRecvd]
//      inviter.roster.participants.size shouldBe 2
//    }
//
//    "inviter should get signal DIDsDiscovered" in { s =>
//      inviter expect signal[Signal.Prepared]
//      inviter expect signal[Signal.ExchangeReqRecvd]
//      inviter expect signal[Signal.DIDCreated]
//    }
//
//    "inviter should send Exchange Response when control message is sent" in { s =>
//      inviter ~ SendExchangeResp(Vector.empty)
//      inviter expect signal[Signal.DIDsDiscovered]
//      inviter.state shouldBe a[State.Completed]
//      inviter expect signal[Signal.ExchangeRespSent]
//    }
//
//    "invitee should receive response and should change state to Complete" in { s=>
//      invitee.state shouldBe a[State.Completed]
//      invitee expect signal[Signal.DIDsDiscovered]
//      invitee expect signal[Signal.Complete]
//    }
//
//    "inviter should get an Ack and transition to complete" in { s =>
//      inviter.state shouldBe a[State.Completed]
//      inviter expect signal[Signal.Complete]
//    }
//  }
//}
