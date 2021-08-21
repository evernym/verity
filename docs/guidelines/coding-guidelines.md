
### General

* copy-paste
  * instead of copy pasting the existing code and doing minor tweaks, move common code into base traits/classes etc and 
    just reuse the code.

* naming convention
  * It needs to be obvious to someone reading the code and seeing a name what the thing does or what the thing is for.
  * Avoid using 'info' or 'detail' in names... it's lazy.
  * [https://netvl.github.io/scala-guidelines/code-style/naming.html]
  * [https://docs.scala-lang.org/style/naming-conventions.html#accessorsmutators]
  
* type aliases
  * don't use Strings or Ints as types unless is completely obvious why, instead create appropriate type alias

* case classes 
  * assumption is that case classes are immutable
  * never use a var when we can get away with a val 
  * don't use vars in case classes
  
* exceptions
  * if at all we have to quietly swallowing any exception, there should be a comment around it 
    which explains why that is done.

* separation of concern
  * each code construct (like functions, traits, classes/objects) should be only doing their very core purpose
  * they may depend/use each other to complete higher level goal, but their all those code should not be in same construct.

* anticipatory code
  * should not create any anticipatory code, only do sufficient coding to meet current requirements

* pushing to master branch
  * code with compile time warnings should not be pushed to master branch.
  
* unnecessary abstraction
  * abstraction has a benefit and cost, but premature abstraction mostly has cost and no benefit. So we should avoid any premature abstraction.  


### Tests
* States should not be shared across tests
* Test should be able to run independent of each other.



###Important Links

* https://docs.scala-lang.org/style/naming-conventions.html#accessorsmutators
* https://github.com/alexandru/scala-best-practices/blob/master/sections/2-language-rules.md

