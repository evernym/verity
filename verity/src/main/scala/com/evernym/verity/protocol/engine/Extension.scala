package com.evernym.verity.protocol.engine

/**
  * Represents an extension or plugin that can be installed in an environment.
  * Comprises a Protocol Definition, a Driver Generator function, and a UI Generator function.
  */
class Extension[A,B](val protoDef: ProtoDef, val driverGen: A => Driver, val uiGen: () => B)