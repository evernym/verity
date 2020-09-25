package com.evernym.verity.actor.testkit

import com.evernym.verity.actor._
import com.evernym.verity.actor.event.serializer.{DefaultEventSerializer, EventCodeMapper, EventSerializer}
import com.evernym.verity.protocol.protocols.tictactoe.{Accepted, Forfeited, Offered}
import com.evernym.verity.protocol.protocols.walletBackup.BackupStored

trait MockEventSerializer {

  //test serializer
  object TestEventMapper extends EventCodeMapper {

    //production event codes are positive, so we can use negative numbers for test events to avoid any conflicts

    override val eventCodeMapping = Map(
      -1 -> TestEventAddData1,
      -2 -> TestEventAddData2,
      -3 -> TestEventDataAdded,
      -4 -> TestItemRemoved,
      -5 -> Offered,
      -6 -> Accepted,
      -7 -> Forfeited,
      -8 -> BackupStored
    )
  }

  object TestEventMapperWithDefaultEventMapper extends EventCodeMapper {

    override val eventCodeMapping =
      TestEventMapper.eventCodeMapping ++ DefaultEventSerializer.eventMapper.eventCodeMapping
  }

  object TestSerializer extends EventSerializer {
    override val eventMapper: EventCodeMapper = TestEventMapper
  }

  object TestSerializerWithDefaultEventMapping extends EventSerializer {
    override val eventMapper: EventCodeMapper = TestEventMapperWithDefaultEventMapper
  }

}
