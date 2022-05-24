This is basic version of event bus adapter implementation (which depends on the basic event store implemented in verity)
Below is the configuration required for the basic event store and corresponding basic adapter implementation.


```
verity {
  eventing {
    event-source = "verity.eventing.basic-source"
    event-sink = "verity.eventing.basic-sink"

    basic-store {
      # the basic store endpoint handler listens on below given port on each node
      # and when it receives events to be published, it sends it to sharded topic actor
      http-listener {
        host = "localhost"
        port = 8900
      }
    }

    basic-source {
      builder-class = "com.evernym.verity.eventing.adapters.basic.consumer.BasicConsumerAdapterBuilder"
      id = "verity"

      topics = ["public.event.ssi.endorsement", "public.event.ssi.endorser"]

      # event consumer's webhook where topic actor will send published events
      http-listener {
        host = "localhost"
        port = 8901
      }
    }

    basic-sink {
      builder-class = "com.evernym.verity.eventing.adapters.basic.producer.BasicProducerAdapterBuilder"
    }
  }    
}
```