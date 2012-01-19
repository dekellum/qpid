/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

#include "ReplicatingSubscription.h"
#include "Logging.h"
#include "qpid/broker/Queue.h"
#include "qpid/framing/AMQFrame.h"
#include "qpid/framing/MessageTransferBody.h"
#include "qpid/log/Statement.h"

namespace qpid {
namespace ha {

using namespace framing;
using namespace broker;
using namespace std;

// FIXME aconway 2011-11-28: review all arugment names, prefixes etc.
// Do we want a common HA prefix?
const string ReplicatingSubscription::QPID_REPLICATING_SUBSCRIPTION("qpid.replicating-subscription");
const string ReplicatingSubscription::QPID_HIGH_SEQUENCE_NUMBER("qpid.high_sequence_number");
const string ReplicatingSubscription::QPID_LOW_SEQUENCE_NUMBER("qpid.low_sequence_number");

const string DOLLAR("$");
const string INTERNAL("-internal");

class ReplicationStateInitialiser
{
  public:
    ReplicationStateInitialiser(
        qpid::framing::SequenceSet& r,
        const qpid::framing::SequenceNumber& s,
        const qpid::framing::SequenceNumber& e) : results(r), start(s), end(e)
    {
        results.add(start, end);
    }

    void operator()(const QueuedMessage& message) {
        if (message.position < start) {
            //replica does not have a message that should still be on the queue
            QPID_LOG(warning, "HA: Replica missing message " << QueuePos(message));
        } else if (message.position >= start && message.position <= end) {
            //i.e. message is within the intial range and has not been dequeued, so remove it from the results
            results.remove(message.position);
        } //else message has not been seen by replica yet so can be ignored here
    }

  private:
    qpid::framing::SequenceSet& results;
    const qpid::framing::SequenceNumber start;
    const qpid::framing::SequenceNumber end;
};

string mask(const string& in)
{
    return DOLLAR + in + INTERNAL;
}

boost::shared_ptr<broker::SemanticState::ConsumerImpl>
ReplicatingSubscription::Factory::create(
    SemanticState* parent,
    const string& name,
    Queue::shared_ptr queue,
    bool ack,
    bool acquire,
    bool exclusive,
    const string& tag,
    const string& resumeId,
    uint64_t resumeTtl,
    const framing::FieldTable& arguments
) {
    return boost::shared_ptr<broker::SemanticState::ConsumerImpl>(
        new ReplicatingSubscription(parent, name, queue, ack, acquire, exclusive, tag, resumeId, resumeTtl, arguments));
}

ReplicatingSubscription::ReplicatingSubscription(
    SemanticState* parent,
    const string& name,
    Queue::shared_ptr queue,
    bool ack,
    bool acquire,
    bool exclusive,
    const string& tag,
    const string& resumeId,
    uint64_t resumeTtl,
    const framing::FieldTable& arguments
) : ConsumerImpl(parent, name, queue, ack, acquire, exclusive, tag,
                 resumeId, resumeTtl, arguments),
    events(new Queue(mask(name))),
    consumer(new DelegatingConsumer(*this))
{
    QPID_LOG(debug, "HA: Replicating subscription " << name << " to " << queue->getName());
    // FIXME aconway 2011-11-25: string constants.
    if (arguments.isSet("qpid.high_sequence_number")) {
        qpid::framing::SequenceNumber hwm = arguments.getAsInt("qpid.high_sequence_number");
        qpid::framing::SequenceNumber lwm;
        if (arguments.isSet("qpid.low_sequence_number")) {
            lwm = arguments.getAsInt("qpid.low_sequence_number");
        } else {
            lwm = hwm;
        }
        qpid::framing::SequenceNumber oldest;
        if (queue->getOldest(oldest)) {
            if (oldest >= hwm) {
                range.add(lwm, --oldest);
            } else if (oldest >= lwm) {
                ReplicationStateInitialiser initialiser(range, lwm, hwm);
                queue->eachMessage(initialiser);
            } else { //i.e. have older message on master than is reported to exist on replica
                QPID_LOG(warning, "HA: Replica  missing message on master");
            }
        } else {
            //local queue (i.e. master) is empty
            range.add(lwm, queue->getPosition());
        }
        QPID_LOG(debug, "HA: Initial set of dequeues for " << queue->getName() << " are " << range
                 << " (lwm=" << lwm << ", hwm=" << hwm << ", current=" << queue->getPosition() << ")");
        //set position of 'cursor'
        position = hwm;
    }
}

bool ReplicatingSubscription::deliver(QueuedMessage& m)
{
    return ConsumerImpl::deliver(m);
}

void ReplicatingSubscription::cancel()
{
    getQueue()->removeObserver(boost::dynamic_pointer_cast<QueueObserver>(shared_from_this()));
}

ReplicatingSubscription::~ReplicatingSubscription() {}

//called before we get notified of the message being available and
//under the message lock in the queue
void ReplicatingSubscription::enqueued(const QueuedMessage& m)
{
    QPID_LOG(trace, "HA: Enqueued message " << QueuePos(m));
    //delay completion
    m.payload->getIngressCompletion().startCompleter();
}

void ReplicatingSubscription::generateDequeueEvent()
{
    string buf(range.encodedSize(),'\0');
    framing::Buffer buffer(&buf[0], buf.size());
    range.encode(buffer);
    range.clear();
    buffer.reset();

    //generate event message
    boost::intrusive_ptr<Message> event = new Message();
    AMQFrame method((MessageTransferBody(ProtocolVersion(), string(), 0, 0)));
    AMQFrame header((AMQHeaderBody()));
    AMQFrame content((AMQContentBody()));
    content.castBody<AMQContentBody>()->decode(buffer, buffer.getSize());
    header.setBof(false);
    header.setEof(false);
    header.setBos(true);
    header.setEos(true);
    content.setBof(false);
    content.setEof(true);
    content.setBos(true);
    content.setEos(true);
    event->getFrames().append(method);
    event->getFrames().append(header);
    event->getFrames().append(content);

    DeliveryProperties* props = event->getFrames().getHeaders()->get<DeliveryProperties>(true);
    props->setRoutingKey("dequeue-event");

    events->deliver(event);
}

//called after the message has been removed from the deque and under
//the message lock in the queue
void ReplicatingSubscription::dequeued(const QueuedMessage& m)
{
    {
        sys::Mutex::ScopedLock l(lock);
        range.add(m.position);
        // FIXME aconway 2011-11-29: q[pos]
        QPID_LOG(trace, "HA: Updated dequeue event to include " << QueuePos(m) << "; subscription is at " << position);
    }
    notify();
    if (m.position > position) {
        m.payload->getIngressCompletion().finishCompleter();
        QPID_LOG(trace, "HA: Completed " << QueuePos(m) << " early due to dequeue");
    }
}

bool ReplicatingSubscription::doDispatch()
{
    {
        sys::Mutex::ScopedLock l(lock);
        if (!range.empty()) {
            generateDequeueEvent();
        }
    }
    bool r1 = events->dispatch(consumer);
    bool r2 = ConsumerImpl::doDispatch();
    return r1 || r2;
}

ReplicatingSubscription::DelegatingConsumer::DelegatingConsumer(ReplicatingSubscription& c) : Consumer(c.getName(), true), delegate(c) {}
ReplicatingSubscription::DelegatingConsumer::~DelegatingConsumer() {}
bool ReplicatingSubscription::DelegatingConsumer::deliver(QueuedMessage& m)
{
    return delegate.deliver(m);
}
void ReplicatingSubscription::DelegatingConsumer::notify() { delegate.notify(); }
bool ReplicatingSubscription::DelegatingConsumer::filter(boost::intrusive_ptr<Message> msg) { return delegate.filter(msg); }
bool ReplicatingSubscription::DelegatingConsumer::accept(boost::intrusive_ptr<Message> msg) { return delegate.accept(msg); }
void ReplicatingSubscription::DelegatingConsumer::cancel() {}
OwnershipToken* ReplicatingSubscription::DelegatingConsumer::getSession() { return delegate.getSession(); }

}} // namespace qpid::broker