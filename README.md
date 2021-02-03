APromise
=====

APromise is an Android Promise implementation based on JavaScript's Promise usage 
and extended to support multi-thread actions and Android's Context.
It uses RxKotlin (Promise) and RxAndroid (APromise) and basically just wraps the `Single` type.

APromise makes it easy to to chain asynchronous actions, from simple to complex async correlations
and with callback chaining (success and cancelation) which is easy to use and control.
APromise makes chaining actions predictable and effective.

In the (hopefully near) future, the underlying pure-kotlin Promise will
be separated and serve an iOS implementation (IPromise) as well, 
using Kotlin's Multiplatform abilities. 
Any developers which desire to take part in this project, please contact me. 

Below is a very simple example, with synchronous execution. 
Note: this example can be used with the underlying (super class) `Promise' model as well, 
as it does not contain Android related code.

```kotlin
APromise.from(5)      //creates an APromise of type Int
        .then {
            it + 10   //add 10 to the result (5)
        }
        .thenMap {
            it > 10   //map type from Int to Boolean
        }
        .then {
            println(
                if (it) "Bigger than 10"
                else "Smaller or equal to 10"
            )
        }
        .execute()
```


And it can just as easily perform async operations.
Note: this time we will use the super class `Promise` as
this example still doesn't use Android code.

```kotlin
Promise.ofAsync( { 5 } )  //creates Promise<Int>, from a supplier this time
       .then {
           it + 10
       }
       .delay(1000)
       .then {
           println("promise finish after 1s delay")
       }
       .execute() //the first supplier ( () -> 5 ) will not
                  //be computed until this the promise is executed
```

### Cancelations

It is possible to cancel the promises, either directly or according to
some computed condition:

```kotlin
val mPromise = Promise.ofAsync( { 5 } )  //creates Promise<Int>, from a supplier this time
       .then {
           println("Promise started")
           it + 10
       }
       .delay(1000)
       .finally { isResolved ->
           println(
               if (isResolved)
                   "promise finish after 1s delay"
               else "promise canceled (or rejected)"
           )
       }
       .execute()

mPromise.cancel()

//prints:
//Promise started
//promise canceled (or rejected)
```

To import project using Gradle:
```kotlin
implementation 'com.github.GuyMichael:APromise:0.1.12'
```


R8 / ProGuard
--------

No requirements at this stage, except for RxKotlin & RxAndroid related rules
which may apply.
Please contact me if you encounter any issues.


License
--------

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

