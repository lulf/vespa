// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "routable.h"
#include <vespa/messagebus/routing/route.h>

namespace mbus {

/**
 * A Message is a question, a Reply is the answer.
 */
class Message : public Routable {
public:
    using time_point = std::chrono::steady_clock::time_point;
    using UP = std::unique_ptr<Message>;

    Message();
    Message(const Message &) = delete;
    Message(Message &&) = delete;
    Message & operator = (const Message &) = delete;
    Message & operator = (Message &&) = delete;

    /**
     * If a message is deleted with elements on the callstack, this destructor
     * will log an error and generate an auto-reply to avoid having the sender
     * wait indefinetly for a reply.
     */
    ~Message() override;

    void swapState(Routable &rhs) override;

    /**
     * Returns the timestamp for when this message was last seen by message
     * bus. If you are using this to determine message expiration, you should
     * use {@link #isExpired()} instead.
     *
     * @return The timestamp this was last seen.
     */
    time_point getTimeReceived() const { return _timeReceived; }

    /**
     * This is a convenience method to call {@link #setTimeReceived(uint64_t)}
     * passing the current time as argument.
     *
     * @return This, to allow chaining.
     */
    Message &setTimeReceivedNow();

    /**
     * Returns the number of milliseconds that remain before this message times
     * out. This value is only updated by the network layer, and is therefore
     * not current. If you are trying to determine message expiration, use
     * {@link this#isExpired()} instead.
     *
     * @return The remaining time in milliseconds.
     */
    duration getTimeRemaining() const { return _timeRemaining; }

    /**
     * Sets the numer of milliseconds that remain before this message times
     * out. Please see comment on {@link this#isExpired()} for more information
     * on how to determine whether or not a message has expired.
     *
     * @param timeRemaining The number of milliseconds until expiration.
     * @return This, to allow chaining.
     */
    Message &setTimeRemaining(duration timeRemaining) { _timeRemaining = timeRemaining; return *this; }

    /**
     * Returns the number of milliseconds that remain right now before this
     * message times out. This is a function of {@link this#getTimeReceived()},
     * {@link this#getTimeRemaining()} and current time. Whenever a message is
     * transmitted by message bus, a new remaining time is calculated and
     * serialized as <code>timeRemaining = timeRemaining - (currentTime -
     * timeReceived)</code>. This means that we are doing an over-estimate of
     * remaining time, as we are only factoring in the time used by the
     * application above message bus.
     *
     * @return The remaining time in milliseconds.
     */
    duration getTimeRemainingNow() const;

    /**
     * Access the route associated with this message.
     *
     * @return reference to internal route object
     */
    Route &getRoute() { return _route; }

    /**
     * Access the route associated with this message.
     *
     * @return reference to internal route object
     */
    const Route &getRoute() const { return _route; }

    /**
     * Set a new route for this routable.
     *
     * @param route The new route.
     * @return This, to allow chaining.
     */
    Message &setRoute(Route route) { _route = std::move(route); return *this; }

    /**
     * Inherited from Routable. Classifies this object as 'not a reply'.
     *
     * @return false
     */
    bool isReply() const override { return false; }

    /**
     * Returns whether or not this message contains a sequence identifier that
     * should be respected, i.e. whether or not this message requires
     * sequencing.
     *
     * @return True to enable sequencing.
     */
    virtual bool hasSequenceId() const { return false; }

    /**
     * Returns the identifier used to order messages. Any two messages that have
     * the same sequence id are ensured to arrive at the recipient in the order
     * they were sent by the client. This value is only respected if the {@link
     * #hasSequenceId()} method returns true.
     *
     * @return The sequence identifier.
     */
    virtual uint64_t getSequenceId() const { return 0; }

    /**
     * Returns whether or not this message contains a sequence bucket that
     * should be respected, i.e. whether or not this message requires
     * bucket-level sequencing.
     *
     * @return True to enable bucket sequencing.
     */
    virtual bool hasBucketSequence() const { return false; }

    /**
     * Obtain the approximate size of this message object in bytes. This enables
     * messagebus to track the size of the send queue in both memory usage and
     * item count. This method returns 1 by default, and must be overridden to
     * enable message size tracking.
     *
     * @return 1
     */
    virtual uint32_t getApproxSize() const { return 1; }

    /**
     * Sets whether or not this message can be resent.
     *
     * @param enabled Resendable flag.
     */
    void setRetryEnabled(bool enabled) { _retryEnabled = enabled; }

    /**
     * Returns whether or not this message can be resent.
     *
     * @return True if this can be resent.
     */
    bool getRetryEnabled() const { return _retryEnabled; }

    /**
     * Returns the number of times the sending of this message has been
     * retried. This is available for inspection so that clients may implement
     * logic to control resending.
     *
     * @see Reply#setRetry This method can be used to request resending that
     *                     differs from the default.
     * @return The retry count.
     */
    uint32_t getRetry() const { return _retry; }

    /**
     * Sets the number of times the sending of this message has been
     * retried. This method only makes sense to modify BEFORE sending it, since
     * its value is not serialized back into any reply that it may create.
     *
     * @param retry The retry count.
     * @return This, to allow chaining.
     */
    Message &setRetry(uint32_t retry) { _retry = retry; return *this; }
private:
    Route         _route;
    time_point    _timeReceived;
    duration      _timeRemaining;
    bool          _retryEnabled;
    uint32_t      _retry;
};

} // namespace mbus

