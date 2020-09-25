package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.protocol.protocols.tictactoe.Board.{CellValue, O, X, empty, cellValueFromStr}

object Board {
  trait CellValue
  case object empty extends CellValue { override def toString = " " }
  case object X extends CellValue
  case object O extends CellValue

  def cellValueFromStr(v: String): CellValue = {
    v match {
      case "X" => X
      case "O" => O
    }
  }

  def getOtherCellValue(cellValue: CellValue): CellValue = cellValue match {
    case X => O
    case O => X
  }

  val positions: Seq[String] = Seq("a1", "b1", "c1", "a2", "b2", "c2", "a3", "b3", "c3")
}

case class Board( a1: CellValue=empty,
                  b1: CellValue=empty,
                  c1: CellValue=empty,
                  a2: CellValue=empty,
                  b2: CellValue=empty,
                  c2: CellValue=empty,
                  a3: CellValue=empty,
                  b3: CellValue=empty,
                  c3: CellValue=empty) {

  def getCellValue(position: String): CellValue = {
    position match {
      case "a1" => a1
      case "a2" => a2
      case "a3" => a3

      case "b1" => b1
      case "b2" => b2
      case "b3" => b3

      case "c1" => c1
      case "c2" => c2
      case "c3" => c3

      case _ => throw new InvalidCell(position)
    }
  }

  def isEmptyCell(position: String): Boolean = {
    getCellValue(position) == empty
  }

  def emptyCells: Seq[String] = {
    Board.positions.filter(isEmptyCell)
  }

  def isFull: Boolean = {
    emptyCells.isEmpty
  }

  /**
    * Calculates a winning symbol, X or O.
    * @return Some(X), Some(O), or None
    */
  def getWinner: Option[CellValue] = {
    val wins = List(
      List(a1, a2, a3),
      List(b1, b2, b3),
      List(c1, c2, c3),
      List(a1, b1, c1),
      List(a2, b2, c2),
      List(a3, b3, c3),
      List(a1, b2, c3),
      List(a3, b2, c1)
    )

    wins.view.flatMap(cvs =>
      Set(X,O).find(ecv => cvs.forall(_ == ecv))
    ).headOption
  }

  def draw: String = {
    val x = List( List(a1,b1,c1), List(a2,b2,c2), List(a3,b3,c3))
    val y = x.map(row => row.mkString("| "," | "," |\n"))
    val z = y.mkString("+---+---+---+\n","+---+---+---+\n","+---+---+---+\n")
    z
  }


  /**
    * Boards are immutable, so updates to a board results in a new board.
    * @param value value of the cell, such as X or O
    * @param atPosition the cell position to change
    * @return a new board with the modification made
    */
  def updateBoard(value: CellValue, atPosition: String): Board = {
    atPosition match {
      case "a1" => copy(a1=value)
      case "a2" => copy(a2=value)
      case "a3" => copy(a3=value)

      case "b1" => copy(b1=value)
      case "b2" => copy(b2=value)
      case "b3" => copy(b3=value)

      case "c1" => copy(c1=value)
      case "c2" => copy(c2=value)
      case "c3" => copy(c3=value)
    }
  }
}
