package com.evernym.verity.vdr

package object service {
  type VDRToolsFactory = VDRFactoryParam => VDRTools
}

case class VDRFactoryParam(libDirLocation: String)
