APromise
=====

APromise is an Android Promise implementation based on JavaScript's Promise usage 
and extended to support multi-thread actions and Android's Context.
It uses RxKotlin (Promise) and RxAndroid (APromise) and basically just wraps the `Single` type.

APromise makes it easy to to chain asynchronous actions, from simple to complex async correlations
and with callback chaining (success and cancelation) which is easy to use and control.
APromise makes chaining actions predictable and effective.

To import project using Gradle:
```kotlin
implementation 'com.github.GuyMichael:APromise:0.1.12'
``` 

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
val mPromise = Promise.ofAsync( { 5 } )
       .then {
           println("Promise started")
           it + 10
       }
       .delay(1000)
       .thenMapOrCancel {     //returning 'null' will cancel the promise
           if (it < 10) null
           else it
       }
       .finally { isResolved ->
           println(
               if (isResolved)
                   "promise finish after 1s delay"
               else "promise canceled (or rejected)"
           )
       }
       .execute()

mPromise.cancel() //cancel directly

//prints:
//Promise started
//promise canceled (or rejected)
```


### Chaining Promises

```kotlin
Promise.ofAsync( { 5 } )
       .thenAwait {            //chain a new promise and also change type to Boolean
           Promise.of(it == 5)
       }
       .then {
           println("promise finished with $it") //'$it' will print 'true' 
       }
       .execute()
```

You can also run multiple promises simultaneously and wait for all to finish:
```kotlin
Promise.all(
        Promise.of(1)
       , Promise.of(2)
       , Promise.ofAsync( { 5 } )
    )
    .then {
        println("All promised resolved")
    }
    .execute() 
```


### Android

APromise can easily handle Context to automatically cancel or reject upon
a Context destruction. You may provide a View or an Activity.

Using a View, to automatically cancel the whole chain once the view is detached from window
or it's Activity gets destroyed:
```kotlin
APromise.ofViewOrCancel(mView)
    .delay(60000)
    .finally { isResolved ->
        println(
           if (isResolved)
               "View is alive for 60s "
           else "View detached or context destroyed"
        )
    } 
    .execute()

mActivity.finish()

//prints:
//View detached... 
```

And for an Activity, using Kotlin extensions:

```kotlin
mActivity.waitForDestroy()    //returns a new APromise
  .then {
      println("mActivity is s now destroyed")
  }
  .execute()
```


### Custom Promises

The `ViewUtils` public class contains useful promises, 
such as `waitForDetach(view: V)` and `isAlive(view: View)`.
`waitForDetach` is a good example of how to create your custom promise
using a good old callback. In this case, Android's `View.OnAttachStateChangeListener` is used
to wait for a `View` to detach, like so:

```kotlin
fun <V : View> waitForDetach(view: V): APromise<V> {
        val viewRef = WeakReference(view)

        return APromise.ofCallback<V, View.OnAttachStateChangeListener>({ promiseCallback ->
            //first argument to 'ofCallback' is the (underlying) listener/callback
            // which is used to create our promise. 
            // This listener will be forwarded to the second argument - a 'finally' callback,
            // to clear this listener (in this case, remove from the view)

            //create Android's listener
            val attachStateListener = View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                //notify promise on success 
                override fun onViewDetachedFromWindow(v: View) {
                    promiseCallback.onSuccess(v as V)
                }
            }

            //attach the Android listener to the view
            view.addOnAttachStateChangeListener(attachStateListener )
           
            return attachStateListener
        }

            //second argument to the 'ofCallback' function is a callback
            // called on 'finally', and destined to remove the listener
        , {
            //unregister (on success, rejections or cancelation) 
            viewRef.get()?.removeOnAttachStateChangeListener(it)
        }) 
}
```
While this code looks big, it can be easily stretched (see original code in `VieeUtils`)
to form a standardization of how a promise-from-callback looks like.
Also, remember that this is a one-time effort. From now on, this whole tiresome view detach process looks
like this (using this method as an extension to any `view`) :

```kotlin
mView.waitForDetach().then { v ->
   //do something with v
}.execute()
```
Nice, isn't it? :)



### Promise for API Handling

A clear usage will be with network services, exactly like you'd use it in JavaScript.
The complete Android library, [ReactiveApp](https://github.com/GuyMichael/ReactiveApp),
uses [Retrofit](https://github.com/square/retrofit) as it's network library (will be replaced
in the future with [Ktor](https://github.com/ktorio/ktor) to support Multiplatform). 

Here is an (shortened) example of how to map Retrofit's `Call` type, which accepts a callback with
the standard 'success' and 'onError' methods, to form APromise:
```kotlin

//we extend Retrofit's Callback to implement it's success and failure
// callbacks and pass on to our promise, through a rx SingleEmitter
class RetrofitApiCallback<T>(
        private val emitter: SingleEmitter<T>
    ): Callback<T> {

    override fun onResponse(call: Call<T>, response: Response<T>) {
        if (response.isSuccessful) {
            emitter.onSuccess(response.body())
        } else {
            emitter.onError(...)
        }
    }

    override fun onFailure(call: Call<T>, e: Throwable) {
        emitter.onError(...)
    } 
}

//now, we wrap Retrofit's Call with our promise
inline fun <reified T : Any> promiseOfCall(call: Call<T>) : APromise<T> {

        return APromise<T>(Single.create { emitter ->
            if( !it.isDisposed) {
                call.enqueue(RetrofitApiCallback(emitter))
            }

        //on promise cancel, cancel the Call
        .catch { hadError = true }
        .finally { resolved ->
            if ( !resolved && !hadError) {
                Logger.d(ApiRequest::class, "API ${call::class.simpleName} is cancelling " +
                    "due to promise cancel/dispose (" +
                    "API ${(if (call.isExecuted) "already" else "is not")} executed)")

                call.cancel()
            }
        }
    }
```
Note: the example above is a simplified version. It is not meant to be used as is, nor
does it compile (the Callback class)  

Again, looks daunting at first, but it is already done for you
in the ReactiveApp library, and from now on, API calls look something like this instead, assuming
`ApiNetflixTitlesGet` is some implementation of the Retrofit Call interface with a function `getTitles()`:
```kotlin
val rService = mRetrofit.create(ApiNetflixTitlesGet::class)

promiseOfCall(rService.getTitles())
   .then { res -> ... }
   .catch { err -> ... }
   .finally {}

   //and we can chain API requests, clearly
  .thenAwait { titles ->
      promiseOfCall(rService.getTitleDetails(
          titles[5]                            //get details of 5th title
      ))
  } 

  //and we can launch a side effect API without waiting for it
  .then { titleDetails ->
      promiseOfCall(analyticsService.sendEvent(titleDetails.name)
  }

  //and we can wait for an Activity to destroy to clear the cache
  .thenAwait(mActivity.waitForDestroy())   //an Activity extension that returns a promise, present in the library
  .then { clearCache() }

  //and then we can do another side effect API, 
  // or cancel conditionally if a view is detached, 
  // or run more API's with `all` and wait for all of them to finish, 
  // or even wait for a Bluetooth connection or location update, wrapped with promises.
  // We can, and should, do virtually anything using promise chaining
}.execute()
```
Note: using the ReactiveApp library, calls will look slightly different, using
an object class named ApiRequest / ApiController, [as can be seen here](https://github.com/GuyMichael/ReactiveAppExample/blob/master/app/src/main/java/com/guymichael/componentapplicationexample/logic/netflix/NetflixLogic.kt) 

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

