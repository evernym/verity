package com.evernym.verity.testkit

import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform

trait PlatformInitialized { this: ProvidesMockPlatform =>
  platform    //to initialize platform (which starts most of the region actors)
}
