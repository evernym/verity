package com.evernym.verity.vdr

package object service {
  type VDRToolsFactory = VDRToolsFactoryParam => VDRTools
}

case class VDRToolsFactoryParam(libDirLocation: String)
