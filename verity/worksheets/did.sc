import com.evernym.verity.util._
def convertAbbrVerkey(did: String, abbrVerkey: String): String = {
  assert(abbrVerkey.startsWith("~"))
  val combined = Base58Util.decode(did).get ++ Base58Util.decode(abbrVerkey.substring(1)).get

  Base58Util.encode(combined)
}

convertAbbrVerkey("4fUDR9R7fjwELRvH9JT6HH", "~R5o13eqaQ6QWdinng8HzVs")