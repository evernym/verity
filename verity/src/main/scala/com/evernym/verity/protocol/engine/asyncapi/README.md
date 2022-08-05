For each different type of 'async apis', there will be two interfaces:

a) <ApiName>Access		(for example: VdrAccess)
* this provides contract/interface between 'protocol' and 'protocol engine'

b) <ApiName>AsyncOps		(for example: VdrAsyncOps)
* this provides contract/interface between 'protocol engine' and 'protocol container'
*Async Op Runner*
The 'AsyncOpRunner' contains code which orchestrates flow of async operations.
* Just before running async operation, it puts the 'handler' on a stack.
* Then it calls 'runAsyncOp' function which will be executed by 'protocol container'.
* Once the result of that 'async operation' is available, that 'protocol container' is expected to call 'executeCallbackHandler' function with the result of that async operation, which will then continue the message handling where it left earlier.