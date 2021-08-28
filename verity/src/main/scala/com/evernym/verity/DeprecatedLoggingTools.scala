package com.evernym.verity

import com.evernym.verity.observability.logs.{LogLayout => Layout}
import com.evernym.verity.observability.logs.{IgnoreLoggerFilter => Filter}

// Wanted to move these classes to a new package for logging instead of being in the root
// but we have direct fully qualified class path references to the object. So think of these
// as being redirects to these classes

@deprecated("LogLayout has move to another package 'com.evernym.verity.observability.logs'", "Aug 2021")
class LogLayout extends Layout
@deprecated("LogLayout has move to another package 'com.evernym.verity.observability.logs'", "Aug 2021")
class IgnoreLoggerFilter extends Filter