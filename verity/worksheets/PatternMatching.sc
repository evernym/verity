
case class MsgType(familyName: String, familyVersion: String, msgName: String, other: Option[String])

case class MsgTypeWrapper (version: String, msgType: MsgType)

def handleMsgTypeWrapper(mtw: MsgTypeWrapper): Unit = {
  println("will handle received msg type: " + mtw)
}


def msgTypeMatchingPF: PartialFunction[MsgTypeWrapper, Unit] = {
  case mtw: MsgTypeWrapper if mtw.version == "1.0" && mtw.msgType == MsgType("pairwise", "1.0", "conn_req", None) =>
    handleMsgTypeWrapper(mtw)
  case x =>
    println("msg type will NOT be handled: " + x)
}


//supported msg type
val connReqMsgType = MsgType("pairwise", "1.0", "conn_req", None)
val connReqMsgTypeWrapper = MsgTypeWrapper("1.0", connReqMsgType)
msgTypeMatchingPF(connReqMsgTypeWrapper)


