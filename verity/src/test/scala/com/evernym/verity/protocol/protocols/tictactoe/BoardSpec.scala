package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.protocol.protocols.tictactoe.Board.{O, X}
import com.evernym.verity.testkit.BasicSpec


class BoardSpec extends BasicSpec {
  "Board.cellValueFromStr" - {
    "should convert cell value to string" in {
      Board.cellValueFromStr("X") shouldBe X
      Board.cellValueFromStr("O") shouldBe O

    }
  }

  "Board.getOtherCellValue" - {
    "should give you the other cell value" in {
      Board.getOtherCellValue(X) shouldBe O
      Board.getOtherCellValue(O) shouldBe X
    }
  }

  "Board" - {
    "should get the correct cell value" in {
      val board = new Board(a1=X, c2=O, b3=Board.empty)
      board.getCellValue("a1") shouldBe X
      board.getCellValue("c2") shouldBe O
      board.getCellValue("b3") shouldBe Board.empty
    }

    "should correctly say a cell is empty" in {
      val board = new Board(a1=X, c2=O, b3=Board.empty)
      board.isEmptyCell("a2") shouldBe true
      board.isEmptyCell("a3") shouldBe true
      board.isEmptyCell("b1") shouldBe true
      board.isEmptyCell("b2") shouldBe true
      board.isEmptyCell("b3") shouldBe true
      board.isEmptyCell("c1") shouldBe true
      board.isEmptyCell("c3") shouldBe true
    }

    "should get a list of empty cells" in {
      val board = new Board(a1=X, c2=O, b3=Board.empty)
      board.emptyCells shouldEqual Seq("b1", "c1", "a2", "b2", "a3", "b3", "c3")
    }

    // TODO: Add tests for getWinner, draw, updateBoard
  }
}