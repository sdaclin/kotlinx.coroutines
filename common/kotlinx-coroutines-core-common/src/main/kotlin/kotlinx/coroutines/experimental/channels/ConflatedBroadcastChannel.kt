/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.channels

import kotlinx.atomicfu.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import kotlinx.coroutines.experimental.internalAnnotations.*
import kotlinx.coroutines.experimental.intrinsics.*
import kotlinx.coroutines.experimental.selects.*

/**
 * Broadcasts the most recently sent element (aka [value]) to all [openSubscription] subscribers.
 *
 * Back-to-send sent elements are _conflated_ -- only the the most recently sent value is received,
 * while previously sent elements **are lost**.
 * Every subscriber immediately receives the most recently sent element.
 * Sender to this broadcast channel never suspends and [offer] always returns `true`.
 *
 * A secondary constructor can be used to create an instance of this class that already holds a value.
 * This channel is also created by `BroadcastChannel(Channel.CONFLATED)` factory function invocation.
 *
 * This implementation is fully lock-free. In this implementation
 * [opening][openSubscription] and [closing][SubscriptionReceiveChannel.close] subscription takes O(N) time, where N is the
 * number of subscribers.
 */
public class ConflatedBroadcastChannel<E>(override val job: Job = Job()) : BroadcastChannel<E> {

    init {
        registerCancellation(job)
    }

    /**
     * Creates an instance of this class that already holds a value.
     *
     * It is as a shortcut to creating an instance with a default constructor and
     * immediately sending an element: `ConflatedBroadcastChannel().apply { offer(value) }`.
     */
    constructor(value: E) : this() {
        _state.lazySet(State<E>(value, null))
    }

    private val _state = atomic<Any>(INITIAL_STATE) // State | Closed
    private val _updating = atomic(0)

    private companion object {
        @JvmField
        val CLOSED = Closed(null)

        @JvmField
        val UNDEFINED = Symbol("UNDEFINED")

        @JvmField
        val INITIAL_STATE = State<Any?>(UNDEFINED, null)
    }

    private class State<E>(
        @JvmField val value: Any?, // UNDEFINED | E
        @JvmField val subscribers: Array<Subscriber<E>>?
    )

    private class Closed(@JvmField val closeCause: Throwable?) {
        val sendException: Throwable get() = closeCause ?: ClosedSendChannelException(DEFAULT_CLOSE_MESSAGE)
        val valueException: Throwable get() = closeCause ?: IllegalStateException(DEFAULT_CLOSE_MESSAGE)
    }

    /**
     * The most recently sent element to this channel.
     *
     * Access to this property throws [IllegalStateException] when this class is constructed without
     * initial value and no value was sent yet or if it was [closed][close] without a cause.
     * It throws the original [close][SendChannel.close] cause exception if the channel has _failed_.
     */
    @Suppress("UNCHECKED_CAST")
    public val value: E get() {
        _state.loop { state ->
            when (state) {
                is Closed -> throw state.valueException
                is State<*> -> {
                    if (state.value === UNDEFINED) throw IllegalStateException("No value")
                    return state.value as E
                }
                else -> error("Invalid state $state")
            }
        }
    }

    /**
     * The most recently sent element to this channel or `null` when this class is constructed without
     * initial value and no value was sent yet or if it was [closed][close].
     */
    @Suppress("UNCHECKED_CAST")
    public val valueOrNull: E? get() {
        val state = _state.value
        when (state) {
            is Closed -> return null
            is State<*> -> {
                if (state.value === UNDEFINED) return null
                return state.value as E
            }
            else -> error("Invalid state $state")
        }
    }

    override val isClosedForSend: Boolean get() = _state.value is Closed
    override val isFull: Boolean get() = false

    @Suppress("UNCHECKED_CAST")
    override fun openSubscription(): SubscriptionReceiveChannel<E> {
        val subscriber = Subscriber<E>(this)
        _state.loop { state ->
            when (state) {
                is Closed -> {
                    subscriber.close(state.closeCause)
                    return subscriber
                }
                is State<*> -> {
                    if (state.value !== UNDEFINED)
                        subscriber.offerInternal(state.value as E)
                    val update = State(state.value, addSubscriber((state as State<E>).subscribers, subscriber))
                    if (_state.compareAndSet(state, update))
                        return subscriber
                }
                else -> error("Invalid state $state")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun closeSubscriber(subscriber: Subscriber<E>) {
        _state.loop { state ->
            when (state) {
                is Closed -> return
                is State<*> -> {
                    val update = State(state.value, removeSubscriber((state as State<E>).subscribers!!, subscriber))
                    if (_state.compareAndSet(state, update))
                        return
                }
                else -> error("Invalid state $state")
            }
        }
    }

    private fun addSubscriber(list: Array<Subscriber<E>>?, subscriber: Subscriber<E>): Array<Subscriber<E>> {
        if (list == null) return Array<Subscriber<E>>(1) { subscriber }
        return list + subscriber
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeSubscriber(list: Array<Subscriber<E>>, subscriber: Subscriber<E>): Array<Subscriber<E>>? {
        val n = list.size
        val i = list.indexOf(subscriber)
        check(i >= 0)
        if (n == 1) return null
        val update = arrayOfNulls<Subscriber<E>>(n - 1)
        arraycopy(list, 0, update, 0, i)
        arraycopy(list, i + 1, update, i, n - i - 1)
        return update as Array<Subscriber<E>>
    }

    @Suppress("UNCHECKED_CAST")
    override fun close(cause: Throwable?): Boolean {
        _state.loop { state ->
            when (state) {
                is Closed -> return false
                is State<*> -> {
                    val update = if (cause == null) CLOSED else Closed(cause)
                    if (_state.compareAndSet(state, update)) {
                        (state as State<E>).subscribers?.forEach { it.close(cause) }
                        return true
                    }
                }
                else -> error("Invalid state $state")
            }
        }
    }

    /**
     * Sends the value to all subscribed receives and stores this value as the most recent state for
     * future subscribers. This implementation never suspends.
     * It throws exception if the channel [isClosedForSend] (see [close] for details).
     */
    suspend override fun send(element: E) {
        offerInternal(element)?.let { throw it.sendException }
    }

    /**
     * Sends the value to all subscribed receives and stores this value as the most recent state for
     * future subscribers. This implementation always returns `true`.
     * It throws exception if the channel [isClosedForSend] (see [close] for details).
     */
    override fun offer(element: E): Boolean {
        offerInternal(element)?.let { throw it.sendException }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun offerInternal(element: E): Closed? {
        // If some other thread is updating the state in its offer operation we assume that our offer had linearized
        // before that offer (we lost) and that offer overwrote us and conflated our offer.
        if (!_updating.compareAndSet(0, 1)) return null
        try {
            _state.loop { state ->
                when (state) {
                    is Closed -> return state
                    is State<*> -> {
                        val update = State(element, (state as State<E>).subscribers)
                        if (_state.compareAndSet(state, update)) {
                            // Note: Using offerInternal here to ignore the case when this subscriber was
                            // already concurrently closed (assume the close had conflated our offer for this
                            // particular subscriber).
                            state.subscribers?.forEach { it.offerInternal(element) }
                            return null
                        }
                    }
                    else -> error("Invalid state $state")
                }
            }
        } finally {
            _updating.value = 0 // reset the updating flag to zero even when something goes wrong
        }
    }

    override val onSend: SelectClause2<E, SendChannel<E>>
        get() = object : SelectClause2<E, SendChannel<E>> {
            override fun <R> registerSelectClause2(select: SelectInstance<R>, param: E, block: suspend (SendChannel<E>) -> R) {
                registerSelectSend(select, param, block)
            }
        }

    private fun <R> registerSelectSend(select: SelectInstance<R>, element: E, block: suspend (SendChannel<E>) -> R) {
        if (!select.trySelect(null)) return
        offerInternal(element)?.let {
            select.resumeSelectCancellableWithException(it.sendException)
            return
        }
        block.startCoroutineUndispatched(receiver = this, completion = select.completion)
    }

    private class Subscriber<E>(
        private val broadcastChannel: ConflatedBroadcastChannel<E>
    ) : ConflatedChannel<E>(), SubscriptionReceiveChannel<E> {
        override fun cancel(cause: Throwable?): Boolean =
            close(cause).also { closed ->
                if (closed) broadcastChannel.closeSubscriber(this)
            }

        public override fun offerInternal(element: E): Any = super.offerInternal(element)
    }
}