package com.evernym.verity.protocol.protocols

package object tictactoe {
  trait SpecHelper {
    def board(s: String): String = {
      s.linesIterator.map(_.trim).filter(_ != "").mkString("","\n","\n")
    }
  }
}
