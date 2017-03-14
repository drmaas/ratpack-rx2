/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.rx2;

import io.reactivex.*;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.exceptions.OnErrorNotImplementedException;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.functions.Consumer;
import io.reactivex.plugins.RxJavaPlugins;
import org.reactivestreams.Publisher;
import ratpack.exec.*;
import ratpack.func.Action;
import ratpack.registry.RegistrySpec;
import ratpack.rx2.internal.DefaultSchedulers;
import ratpack.rx2.internal.ExecControllerBackedScheduler;
import ratpack.stream.Streams;
import ratpack.stream.TransformablePublisher;
import ratpack.util.Exceptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides integration with <a href="https://github.com/Netflix/RxJava">RxJava2</a>.
 * <p>
 * <b>IMPORTANT:</b> the {@link #initialize()} method must be called to fully enable integration.
 * <p>
 * The methods of this class provide bi-directional conversion between Ratpack's {@link Promise} and RxJava's {@link Observable}.
 * This allows Ratpack promise based API to be integrated into an RxJava based app and vice versa.
 * <p>
 * Conveniently, the {@link #initialize()} method installs an RxJava extension that provides a default error handling strategy for observables that integrates with Ratpack's execution model.
 * <p>
 * To test observable based services that use Ratpack's execution semantics, use the {@code ExecHarness} and convert the observable back to a promise with {@link #promise(Observable)}.
 * <p>
 * The methods in this class are also provided as <a href="http://docs.groovy-lang.org/latest/html/documentation/#_extension_modules">Groovy Extensions</a>.
 * When using Groovy, each static method in this class is able to act as an instance-level method against the {@link Observable} type.
 */
public abstract class RxRatpack {

  private RxRatpack() {
  }

  /**
   * Registers an {@link RxJavaPlugins#errorHandler} with RxJava that provides a default error handling strategy of forwarding exceptions to the execution error handler.
   * <p>
   * This method is idempotent.
   * It only needs to be called once per JVM, regardless of how many Ratpack applications are running within the JVM.
   * <p>
   * For a Java application, a convenient place to call this is in the handler factory implementation.
   * <pre class="java">{@code
   * import ratpack.error.ServerErrorHandler;
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.embed.EmbeddedApp;
   * import rx.Observable;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String... args) throws Exception {
   * RxRatpack.initialize(); // must be called once for the life of the JVM
   * <p>
   * EmbeddedApp.fromHandlers(chain -> chain
   * .register(s -> s
   * .add(ServerErrorHandler.class, (ctx, throwable) ->
   * ctx.render("caught by error handler: " + throwable.getMessage())
   * )
   * )
   * .get(ctx -> Observable.<String>error(new Exception("!")).subscribe(ctx::render))
   * ).test(httpClient ->
   * assertEquals("caught by error handler: !", httpClient.getText())
   * );
   * }
   * }
   * }</pre>
   */
  @SuppressWarnings("unchecked")
  public static void initialize() {
    RxJavaPlugins.setErrorHandler(new ErrorHandler());
    RxJavaPlugins.setInitComputationSchedulerHandler(c -> DefaultSchedulers.getComputationScheduler());
    RxJavaPlugins.setInitIoSchedulerHandler(c -> DefaultSchedulers.getIoScheduler());
    RxJavaPlugins.setOnObservableSubscribe((observable, observer) -> new ExecutionBackedObserver(observer));
  }

  /**
   * Converts a {@link Promise} into an {@link Observable}.
   * <p>
   * The returned observable emits the promise's single value if it succeeds, and emits the error (i.e. via {@code onError()}) if it fails.
   * <p>
   * This method works well as a method reference to the {@link Promise#to(ratpack.func.Function)} method.
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.exec.Promise;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static String value;
   * public static void main(String... args) throws Exception {
   * ExecHarness.runSingle(e ->
   * Promise.value("hello world")
   * .to(RxRatpack::observe)
   * .map(String::toUpperCase)
   * .subscribe(s -> value = s)
   * );
   * <p>
   * assertEquals("HELLO WORLD", value);
   * }
   * }
   * }</pre>
   *
   * @param promise the promise
   * @param <T>     the type of value promised
   * @return an observable for the promised value
   */
  public static <T> Observable<T> observe(Promise<T> promise) {
    return Observable.create(subscriber ->
      promise.onError(subscriber::onError).then(value -> {
        subscriber.onNext(value);
        subscriber.onComplete();
      })
    );
  }

  /**
   * Converts a {@link Operation} into an {@link Observable}.
   * <p>
   * The returned observable emits completes upon completion of the operation without emitting a value, and emits the error (i.e. via {@code onError()}) if it fails.
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.exec.Operation;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import static org.junit.Assert.assertTrue;
   * <p>
   * public class Example {
   * public static boolean executed;
   * public static void main(String... args) throws Exception {
   * ExecHarness.runSingle(e ->
   * Operation.of(() -> executed = true)
   * .to(RxRatpack::observe)
   * .subscribe()
   * );
   * <p>
   * assertTrue(executed);
   * }
   * }
   * }</pre>
   *
   * @param operation the operation
   * @return an observable for the operation
   */
  public static Observable<Void> observe(Operation operation) {
    return Observable.create(subscriber -> operation.onError(subscriber::onError).then(subscriber::onComplete));
  }

  /**
   * Converts a {@link Promise} for an iterable into an {@link Observable}.
   * <p>
   * The promised iterable will be emitted to the observer one element at a time, like {@link Observable#fromIterable(Iterable)}.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.exec.Promise;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import java.util.Arrays;
   * import java.util.LinkedList;
   * import java.util.List;
   * <p>
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String... args) throws Exception {
   * final List<String> items = new LinkedList<>();
   * ExecHarness.runSingle(e ->
   * Promise.value(Arrays.asList("foo", "bar"))
   * .to(RxRatpack::observeEach)
   * .subscribe(items::add)
   * );
   * <p>
   * assertEquals(Arrays.asList("foo", "bar"), items);
   * }
   * }
   * }</pre>
   *
   * @param promise the promise
   * @param <T>     the element type of the promised iterable
   * @param <I>     the type of iterable
   * @return an observable for each element of the promised iterable
   * @see #observe(ratpack.exec.Promise)
   */
  public static <T, I extends Iterable<T>> Observable<T> observeEach(Promise<I> promise) {
    return Observable.merge(observe(promise).map(Observable::fromIterable));
  }

  /**
   * Converts an {@link Observable} into a {@link Promise}, for all of the observable's items.
   * <p>
   * This method can be used to simply adapt an observable to a promise, but can also be used to bind an observable to the current execution.
   * It is sometimes more convenient to use {@link #promise(ObservableOnSubscribe)} over this method.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import java.util.List;
   * import java.util.Arrays;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * List<String> results = ExecHarness.yieldSingle(execution ->
   * RxRatpack.promise(new AsyncService().observe("foo"))
   * ).getValue();
   * <p>
   * assertEquals(Arrays.asList("foo"), results);
   * }
   * }
   * }</pre>
   * <p>
   * <p>
   * This method uses {@link Observable#toList()} to collect the observable's contents into a list.
   * It therefore should not be used with observables with many or infinite items.
   * <p>
   * If it is expected that the observable only emits one element, it is typically more convenient to use {@link #promiseSingle(Observable)}.
   * <p>
   * If the observable emits an error, the returned promise will fail with that error.
   * <p>
   * This method must be called during an execution.
   *
   * @param observable the observable
   * @param <T>        the type of the value observed
   * @return a promise that returns all values from the observable
   * @throws UnmanagedThreadException if called outside of an execution
   * @see #promiseSingle(Observable)
   */
  public static <T> Promise<List<T>> promise(Observable<T> observable) throws UnmanagedThreadException {
    return Promise.async(f -> observable.toList().subscribe(f::success, f::error));
  }

  /**
   * Converts an {@link Observable} into a {@link Promise}, for all of the observable's items.
   * <p>
   * This method can be used to simply adapt an observable to a promise, but can also be used to bind an observable to the current execution.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import java.util.List;
   * import java.util.Arrays;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * List<String> results = ExecHarness.yieldSingle(execution ->
   * new AsyncService().observe("foo").extend(RxRatpack::promise)
   * ).getValue();
   * <p>
   * assertEquals(Arrays.asList("foo"), results);
   * }
   * }
   * }</pre>
   * <p>
   * <p>
   * This method uses {@link Observable#toList()} to collect the observable's contents into a list.
   * It therefore should not be used with observables with many or infinite items.
   * <p>
   * If it is expected that the observable only emits one element, it is typically more convenient to use {@link #promiseSingle(Observable)}.
   * <p>
   * If the observable emits an error, the returned promise will fail with that error.
   * <p>
   * This method must be called during an execution.
   *
   * @param onSubscribe the on subscribe function
   * @param <T>         the type of the value observed
   * @return a promise that returns all values from the observable
   * @throws UnmanagedThreadException if called outside of an execution
   * @see #promiseSingle(Observable)
   * @see #promise(Observable)
   */
  public static <T> Promise<List<T>> promise(ObservableOnSubscribe<T> onSubscribe) throws UnmanagedThreadException {
    return promise(Observable.create(onSubscribe));
  }

  /**
   * Converts an {@link Observable} into a {@link Promise}, for the observable's single item.
   * <p>
   * This method can be used to simply adapt an observable to a promise, but can also be used to bind an observable to the current execution.
   * It is sometimes more convenient to use {@link #promiseSingle(ObservableOnSubscribe)} over this method.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * String result = ExecHarness.yieldSingle(execution ->
   * RxRatpack.promiseSingle(new AsyncService().observe("foo"))
   * ).getValue();
   * <p>
   * assertEquals("foo", result);
   * }
   * }
   * }</pre>
   * <p>
   * <p>
   * This method uses {@link Observable#singleElement()} to enforce that the observable only emits one item.
   * If the observable may be empty, then use {@link Observable#single(Object)} to provide a default value.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String[] args) throws Throwable {
   * String result = ExecHarness.yieldSingle(execution ->
   * RxRatpack.promiseSingle(Observable.<String>empty().singleOrDefault("foo"))
   * ).getValue();
   * assertEquals("foo", result);
   * }
   * }
   * }</pre>
   * <p>
   * <p>
   * If it is expected that the observable may emit more than one element, use {@link #promise(Observable)}.
   * <p>
   * If the observable emits an error, the returned promise will fail with that error.
   * If the observable emits no items, the returned promise will fail with a {@link java.util.NoSuchElementException}.
   * If the observable emits more than one item, the returned promise will fail with an {@link IllegalStateException}.
   * <p>
   * This method must be called during an execution.
   *
   * @param observable the observable
   * @param <T>        the type of the value observed
   * @return a promise that returns the sole value from the observable
   * @see #promise(Observable)
   * @see #promiseSingle(ObservableOnSubscribe)
   */
  public static <T> Promise<T> promiseSingle(Observable<T> observable) throws UnmanagedThreadException {
    return Promise.async(f -> observable.singleElement().subscribe(f::success, f::error));
  }

  /**
   * @see RxRatpack#promiseSingle(Observable)
   *
   * @param single
   * @param <T>
   * @return
   * @throws UnmanagedThreadException
   */
  public static <T> Promise<T> promiseSingle(Single<T> single) throws UnmanagedThreadException {
    return Promise.async(f -> single.subscribe(f::success, f::error));
  }

  /**
   * Converts an {@link Observable} into a {@link Promise}, for the observable's single item.
   * <p>
   * This method can be used to simply adapt an observable to a promise, but can also be used to bind an observable to the current execution.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * String result = ExecHarness.yieldSingle(execution ->
   * new AsyncService().observe("foo").extend(RxRatpack::promiseSingle)
   * ).getValue();
   * <p>
   * assertEquals("foo", result);
   * }
   * }
   * }</pre>
   * <p>
   * This method uses {@link Observable#singleElement()} to enforce that the observable only emits one item.
   * If the observable may be empty, then use {@link Observable#single(Object)} to provide a default value.
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String[] args) throws Throwable {
   * String result = ExecHarness.yieldSingle(execution ->
   * Observable.<String>empty().singleOrDefault("foo").extend(RxRatpack::promiseSingle)
   * ).getValue();
   * assertEquals("foo", result);
   * }
   * }
   * }</pre>
   * If it is expected that the observable may emit more than one element, use {@link #promise(ObservableOnSubscribe)}.
   * <p>
   * If the observable emits an error, the returned promise will fail with that error.
   * If the observable emits no items, the returned promise will fail with a {@link java.util.NoSuchElementException}.
   * If the observable emits more than one item, the returned promise will fail with an {@link IllegalStateException}.
   * <p>
   * This method must be called during an execution.
   *
   * @param onSubscribe the on subscribe function
   * @param <T>         the type of the value observed
   * @return a promise that returns the sole value from the observable
   * @see #promise(ObservableOnSubscribe)
   * @see #promiseSingle(Observable)
   */
  public static <T> Promise<T> promiseSingle(ObservableOnSubscribe<T> onSubscribe) throws UnmanagedThreadException {
    return promiseSingle(Observable.create(onSubscribe));
  }

  /**
   * Converts an {@link Observable} into a {@link Publisher}, for all of the observable's items.
   * <p>
   * This method can be used to simply adapt an observable to a ReactiveStreams publisher.
   * It is sometimes more convenient to use {@link #publisher(ObservableOnSubscribe)} over this method.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.stream.Streams;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import java.util.List;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * List<String> result = ExecHarness.yieldSingle(execution ->
   * RxRatpack.publisher(new AsyncService().observe("foo")).toList()
   * ).getValue();
   * assertEquals("foo", result.get(0));
   * }
   * }
   * }</pre>
   *
   * @param observable the observable
   * @param <T>        the type of the value observed
   * @return a ReactiveStreams publisher containing each value of the observable
   */
  public static <T> TransformablePublisher<T> publisher(Observable<T> observable) {
    return Streams.transformable(observable.toFlowable(BackpressureStrategy.BUFFER));
  }

  /**
   * Converts an {@link Observable} into a {@link Publisher}, for all of the observable's items.
   * <p>
   * This method can be used to simply adapt an observable to a ReactiveStreams publisher.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.stream.Streams;
   * import ratpack.test.exec.ExecHarness;
   * import rx.Observable;
   * import java.util.List;
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * static class AsyncService {
   * public <T> Observable<T> observe(final T value) {
   * return Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext(value);
   * subscriber.onCompleted();
   * }).start()
   * );
   * }
   * }
   * <p>
   * public static void main(String[] args) throws Throwable {
   * List<String> result = ExecHarness.yieldSingle(execution ->
   * new AsyncService().observe("foo").extend(RxRatpack::publisher).toList()
   * ).getValue();
   * assertEquals("foo", result.get(0));
   * }
   * }
   * }</pre>
   *
   * @param onSubscribe the on subscribe function
   * @param <T>         the type of the value observed
   * @return a ReactiveStreams publisher containing each value of the observable
   */
  public static <T> TransformablePublisher<T> publisher(ObservableOnSubscribe<T> onSubscribe) {
    return publisher(Observable.create(onSubscribe));
  }

  /**
   * Binds the given observable to the current execution, allowing integration of third-party asynchronous observables with Ratpack's execution model.
   * <p>
   * This method is useful when you want to consume an asynchronous observable within a Ratpack execution, as an observable.
   * It is just a combination of {@link #promise(Observable)} and {@link #observeEach(Promise)}.
   * <p>
   * <pre class="java">{@code
   * import rx.Observable;
   * import ratpack.test.exec.ExecHarness;
   * import ratpack.rx.RxRatpack;
   * import java.util.Arrays;
   * import java.util.List;
   * import static org.junit.Assert.*;
   * <p>
   * public class Example {
   * public static void main(String... args) throws Exception {
   * Observable<String> asyncObservable = Observable.create(subscriber ->
   * new Thread(() -> {
   * subscriber.onNext("foo");
   * subscriber.onNext("bar");
   * subscriber.onCompleted();
   * }).start()
   * );
   * <p>
   * List<String> strings = ExecHarness.yieldSingle(e ->
   * RxRatpack.promise(asyncObservable.compose(RxRatpack::bindExec))
   * ).getValue();
   * <p>
   * assertEquals(Arrays.asList("foo", "bar"), strings);
   * }
   * }
   * }</pre>
   * <p>
   *
   * @param source the observable source
   * @param <T>    the type of item observed
   * @return an observable stream equivalent to the given source
   * @see #observeEach(Promise)
   * @see #promise(Observable)
   */
  public static <T> Observable<T> bindExec(Observable<T> source) {
    return Exceptions.uncheck(() -> promise(source).to(RxRatpack::observeEach));
  }

  /**
   * Parallelize an observable by forking it's execution onto a different Ratpack compute thread and automatically binding
   * the result back to the original execution.
   * <p>
   * This method can be used for simple parallel processing.  It's behavior is similar to the
   * <a href="http://reactivex.io/documentation/operators/subscribeon.html">subscribeOn</a> but allows the use of
   * Ratpack compute threads.  Using <code>fork</code> modifies the execution of the upstream observable.
   * <p>
   * This is different than <code>forkEach</code> which modifies where the downstream is executed.
   * <p>
   * <pre class="java">{@code
   * import ratpack.func.Pair;
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import rx.Observable;
   * <p>
   * import static org.junit.Assert.assertEquals;
   * import static org.junit.Assert.assertNotEquals;
   * <p>
   * public class Example {
   * public static void main(String[] args) throws Exception {
   * RxRatpack.initialize();
   * <p>
   * try (ExecHarness execHarness = ExecHarness.harness(6)) {
   * Integer sum = execHarness.yield(execution -> {
   * final String originalComputeThread = Thread.currentThread().getName();
   * <p>
   * Observable<Integer> unforkedObservable = Observable.just(1);
   * <p>
   * // `map` is executed upstream from the fork; that puts it on another parallel compute thread
   * Observable<Pair<Integer, String>> forkedObservable = Observable.just(2)
   * .map((val) -> Pair.of(val, Thread.currentThread().getName()))
   * .compose(RxRatpack::fork);
   * <p>
   * return RxRatpack.promiseSingle(
   * Observable.zip(unforkedObservable, forkedObservable, (Integer intVal, Pair<Integer, String> pair) -> {
   * String forkedComputeThread = pair.right;
   * assertNotEquals(originalComputeThread, forkedComputeThread);
   * return intVal + pair.left;
   * })
   * );
   * }).getValueOrThrow();
   * <p>
   * assertEquals(sum.intValue(), 3);
   * }
   * }
   * }
   * }</pre>
   *
   * @param observable the observable sequence to execute on a different compute thread
   * @param <T>        the element type
   * @return an observable on the compute thread that <code>fork</code> was called from
   * @see #forkEach(Observable)
   * @since 1.4
   */
  public static <T> Observable<T> fork(Observable<T> observable) {
    return observeEach(promise(observable).fork());
  }

  /**
   * A variant of {@link #fork} that allows access to the registry of the forked execution inside an {@link Action}.
   * <p>
   * This allows the insertion of objects via {@link RegistrySpec#add} that will be available to the forked observable.
   * <p>
   * You do not have access to the original execution inside the {@link Action}.
   * <p>
   * <pre class="java">{@code
   * import ratpack.exec.Execution;
   * import ratpack.registry.RegistrySpec;
   * import ratpack.rx.RxRatpack;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import rx.Observable;
   * <p>
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String[] args) throws Exception {
   * RxRatpack.initialize();
   * <p>
   * try (ExecHarness execHarness = ExecHarness.harness(6)) {
   * String concatenatedResult = execHarness.yield(execution -> {
   * <p>
   * Observable<String> notYetForked = Observable.just("foo")
   * .map((value) -> value + Execution.current().get(String.class));
   * <p>
   * Observable<String> forkedObservable = RxRatpack.fork(
   * notYetForked,
   * (RegistrySpec registrySpec) -> registrySpec.add("bar")
   * );
   * <p>
   * return RxRatpack.promiseSingle(forkedObservable);
   * }).getValueOrThrow();
   * <p>
   * assertEquals(concatenatedResult, "foobar");
   * }
   * }
   * }
   * }</pre>
   *
   * @param observable         the observable sequence to execute on a different compute thread
   * @param doWithRegistrySpec an Action where objects can be inserted into the registry of the forked execution
   * @param <T>                the element type
   * @return an observable on the compute thread that <code>fork</code> was called from
   * @throws Exception
   * @see #fork(Observable)
   * @since 1.4
   */
  public static <T> Observable<T> fork(Observable<T> observable, Action<? super RegistrySpec> doWithRegistrySpec) throws Exception {
    return observeEach(promise(observable).fork(execSpec -> execSpec.register(doWithRegistrySpec)));
  }


  /**
   * Parallelize an observable by creating a new Ratpack execution for each element.
   * <p>
   * <pre class="java">{@code
   * import ratpack.rx.RxRatpack;
   * import ratpack.util.Exceptions;
   * import ratpack.test.exec.ExecHarness;
   * <p>
   * import rx.Observable;
   * <p>
   * import java.util.List;
   * import java.util.Arrays;
   * import java.util.LinkedList;
   * import java.util.Collection;
   * import java.util.Collections;
   * import java.util.concurrent.CyclicBarrier;
   * <p>
   * import static org.junit.Assert.assertEquals;
   * <p>
   * public class Example {
   * public static void main(String[] args) throws Exception {
   * RxRatpack.initialize();
   * <p>
   * CyclicBarrier barrier = new CyclicBarrier(5);
   * <p>
   * try (ExecHarness execHarness = ExecHarness.harness(6)) {
   * List<Integer> values = execHarness.yield(execution ->
   * RxRatpack.promise(
   * Observable.just(1, 2, 3, 4, 5)
   * .compose(RxRatpack::forkEach) // parallelize
   * .doOnNext(value -> Exceptions.uncheck(() -> barrier.await())) // wait for all values
   * .map(integer -> integer.intValue() * 2)
   * .serialize()
   * )
   * ).getValue();
   * <p>
   * List<Integer> sortedValues = new LinkedList<>(values);
   * Collections.sort(sortedValues);
   * assertEquals(Arrays.asList(2, 4, 6, 8, 10), sortedValues);
   * }
   * }
   * }
   * }</pre>
   *
   * @param observable the observable sequence to process each element of in a forked execution
   * @param <T>        the element type
   * @return an observable
   */
  public static <T> Observable<T> forkEach(Observable<T> observable) {
    return RxRatpack.forkEach(observable, Action.noop());
  }

  /**
   * A variant of {@link #forkEach} that allows access to the registry of each forked execution inside an {@link Action}.
   * <p>
   * This allows the insertion of objects via {@link RegistrySpec#add} that will be available to every forked observable.
   * <p>
   * You do not have access to the original execution inside the {@link Action}.
   *
   * @param observable         the observable sequence to process each element of in a forked execution
   * @param doWithRegistrySpec an Action where objects can be inserted into the registry of the forked execution
   * @param <T>                the element type
   * @return an observable
   * @see #forkEach(Observable)
   * @see #fork(Observable, Action)
   * @since 1.4
   */
  public static <T> Observable<T> forkEach(Observable<T> observable, Action<? super RegistrySpec> doWithRegistrySpec) {
    return observable.lift(downstream -> new Observer<T>() {

      private final AtomicInteger wip = new AtomicInteger(1);
      private final AtomicBoolean closed = new AtomicBoolean();
      private Disposable disposable;

      @Override
      public void onSubscribe(Disposable d) {
        this.disposable = d;
      }

      @Override
      public void onComplete() {
        maybeDone();
      }

      @Override
      public void onError(final Throwable e) {
        terminate(() -> downstream.onError(e));
      }

      private void maybeDone() {
        if (wip.decrementAndGet() == 0) {
          terminate(downstream::onComplete);
        }
      }

      private void terminate(Runnable runnable) {
        if (closed.compareAndSet(false, true)) {
          runnable.run();
        }
      }

      @Override
      public void onNext(final T t) {
        // Avoid the overhead of creating executions if downstream is no longer interested
        if (disposable.isDisposed() || closed.get()) {
          return;
        }

        wip.incrementAndGet();
        Execution.fork()
          .register(doWithRegistrySpec)
          .onComplete(e -> this.maybeDone())
          .onError(this::onError)
          .start(e -> {
            if (!closed.get()) {
              downstream.onNext(t);
            }
          });
      }
    });
  }

  /**
   * A scheduler that uses the application event loop and initialises each job as an {@link ratpack.exec.Execution} (via {@link ExecController#fork()}).
   *
   * @param execController the execution controller to back the scheduler
   * @return a scheduler
   */
  public static Scheduler scheduler(ExecController execController) {
    return new ExecControllerBackedScheduler(execController);
  }

  /**
   * A scheduler that uses the application event loop and initialises each job as an {@link ratpack.exec.Execution} (via {@link ExecController#fork()}).
   * <p>
   * That same as {@link #scheduler(ExecController)}, but obtains the exec controller via {@link ExecController#require()}.
   *
   * @return a scheduler
   */
  public static Scheduler scheduler() {
    return scheduler(ExecController.require());
  }

  private static class ErrorHandler implements Consumer<Throwable> {
    @Override
    public void accept(Throwable e) throws Exception {
      if (e instanceof OnErrorNotImplementedException) {
        Promise.error(e.getCause()).then(Action.noop());
      } else if (e instanceof UndeliverableException) {
        Promise.error(e.getCause()).then(Action.noop());
      } else {
        Promise.error(e).then(Action.noop());
      }
    }
  }

  private static class ExecutionBackedObserver<T> implements Observer<T> {
    private final Observer<? super T> observer;

    public ExecutionBackedObserver(Observer<? super T> observer) {
      this.observer = observer;
    }

    @Override
    public void onComplete() {
      try {
        observer.onComplete();
      } catch (final OnErrorNotImplementedException e) {
        Promise.error(e.getCause()).then(Action.noop());
      }
    }

    @Override
    public void onError(Throwable e) {
      try {
        observer.onError(e);
      } catch (final OnErrorNotImplementedException e2) {
        Promise.error(e2.getCause()).then(Action.noop());
      } catch (Exception e2) {
        Promise.error(new CompositeException(e, e2)).then(Action.noop());
      }
    }

    @Override
    public void onSubscribe(Disposable d) {
      try {
        observer.onSubscribe(d);
      } catch (final OnErrorNotImplementedException e) {
        Promise.error(e.getCause()).then(Action.noop());
      }
    }

    @Override
    public void onNext(T t) {
      try {
        observer.onNext(t);
      } catch (final OnErrorNotImplementedException e) {
        Promise.error(e.getCause()).then(Action.noop());
      }
    }
  }

}
