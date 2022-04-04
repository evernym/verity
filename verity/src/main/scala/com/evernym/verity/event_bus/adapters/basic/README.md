This is default, basic, non-persistent and non-production version of the event bus adapter implementation.
This is initial version and may/will get modified if needed.

For production, it is recommended to use kafka adapters.

Below is the configuration required for this basic adapter (and its event storage) implementation.

```
verity {
  event-bus {
    basic {
      store {
        http-listener {
          host = "localhost"
          port = 1233
        }
      }
      consumer {
        id = "verity"
        topics = ["topic1", "topic2"]
        http-listener {
          host = "localhost"
          port = 1233
        }
      }
    }
  }    
}
```