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
#include "WiringReplicator.h"
#include "QueueReplicator.h"
#include "qpid/broker/Broker.h"
#include "qpid/broker/Queue.h"
#include "qpid/broker/Link.h"
#include "qpid/framing/FieldTable.h"
#include "qpid/log/Statement.h"
#include "qpid/amqp_0_10/Codecs.h"
#include "qpid/broker/SessionHandler.h"
#include "qpid/framing/reply_exceptions.h"
#include "qpid/framing/MessageTransferBody.h"
#include "qmf/org/apache/qpid/broker/EventBind.h"
#include "qmf/org/apache/qpid/broker/EventExchangeDeclare.h"
#include "qmf/org/apache/qpid/broker/EventExchangeDelete.h"
#include "qmf/org/apache/qpid/broker/EventQueueDeclare.h"
#include "qmf/org/apache/qpid/broker/EventQueueDelete.h"
#include "qmf/org/apache/qpid/broker/EventSubscribe.h"

namespace qpid {
namespace ha {

using qmf::org::apache::qpid::broker::EventBind;
using qmf::org::apache::qpid::broker::EventExchangeDeclare;
using qmf::org::apache::qpid::broker::EventExchangeDelete;
using qmf::org::apache::qpid::broker::EventQueueDeclare;
using qmf::org::apache::qpid::broker::EventQueueDelete;
using qmf::org::apache::qpid::broker::EventSubscribe;
using namespace framing;
using std::string;
using types::Variant;
using namespace broker;

namespace {

const string QPID_WIRING_REPLICATOR("qpid.wiring-replicator");
const string QPID_REPLICATE("qpid.replicate");

const string CLASS_NAME("_class_name");
const string EVENT("_event");
const string OBJECT_NAME("_object_name");
const string PACKAGE_NAME("_package_name");
const string QUERY_RESPONSE("_query_response");
const string SCHEMA_ID("_schema_id");
const string VALUES("_values");

const string ALTEX("altEx");
const string ARGS("args");
const string ARGUMENTS("arguments");
const string AUTODEL("autoDel");
const string AUTODELETE("autoDelete");
const string BIND("bind");
const string BINDING("binding");
const string CREATED("created");
const string DISP("disp");
const string DURABLE("durable");
const string EXCHANGE("exchange");
const string EXNAME("exName");
const string EXTYPE("exType");
const string KEY("key");
const string NAME("name");
const string QNAME("qName");
const string QUEUE("queue");
const string RHOST("rhost");
const string TYPE("type");
const string USER("user");

const string AGENT_IND_EVENT_ORG_APACHE_QPID_BROKER("agent.ind.event.org_apache_qpid_broker.#");
const string QMF2("qmf2");
const string QMF_CONTENT("qmf.content");
const string QMF_DEFAULT_TOPIC("qmf.default.topic");
const string QMF_OPCODE("qmf.opcode");

const string _WHAT("_what");
const string _CLASS_NAME("_class_name");
const string _PACKAGE_NAME("_package_name");
const string _SCHEMA_ID("_schema_id");
const string OBJECT("OBJECT");
const string ORG_APACHE_QPID_BROKER("org.apache.qpid.broker");
const string QMF_DEFAULT_DIRECT("qmf.default.direct");
const string _QUERY_REQUEST("_query_request");
const string BROKER("broker");

bool isQMFv2(const Message& message) {
    const framing::MessageProperties* props = message.getProperties<framing::MessageProperties>();
    return props && props->getAppId() == QMF2;
}

template <class T> bool match(Variant::Map& schema) {
    return T::match(schema[CLASS_NAME], schema[PACKAGE_NAME]);
}

// FIXME aconway 2011-11-24: this should be a class.
enum ReplicateLevel { RL_NONE=0, RL_WIRING, RL_ALL };
const string S_NONE="none";
const string S_WIRING="wiring";
const string S_ALL="all";

ReplicateLevel replicateLevel(const string& str) {
    // FIXME aconway 2011-11-24: case insenstive comparison.
    ReplicateLevel rl = RL_NONE;
    if (str == S_WIRING) rl = RL_WIRING;
    else if (str == S_ALL) rl = RL_ALL;
    return rl;
}

ReplicateLevel replicateLevel(const framing::FieldTable& f) {
    if (f.isSet(QPID_REPLICATE)) return replicateLevel(f.getAsString(QPID_REPLICATE));
    else return RL_NONE;
}

ReplicateLevel replicateLevel(const Variant::Map& m) {
    Variant::Map::const_iterator i = m.find(QPID_REPLICATE);
    if (i != m.end()) return replicateLevel(i->second.asString());
    else return RL_NONE;
}

void sendQuery(const string className, const string& queueName, SessionHandler& sessionHandler) {
    framing::AMQP_ServerProxy peer(sessionHandler.out);
    Variant::Map request;
    request[_WHAT] = OBJECT;
    Variant::Map schema;
    schema[_CLASS_NAME] = className;
    schema[_PACKAGE_NAME] = ORG_APACHE_QPID_BROKER;
    request[_SCHEMA_ID] = schema;

    AMQFrame method((MessageTransferBody(ProtocolVersion(), QMF_DEFAULT_DIRECT, 0, 0)));
    method.setBof(true);
    method.setEof(false);
    method.setBos(true);
    method.setEos(true);
    AMQHeaderBody headerBody;
    MessageProperties* props = headerBody.get<MessageProperties>(true);
    props->setReplyTo(qpid::framing::ReplyTo("", queueName));
    props->setAppId(QMF2);
    props->getApplicationHeaders().setString(QMF_OPCODE, _QUERY_REQUEST);
    headerBody.get<qpid::framing::DeliveryProperties>(true)->setRoutingKey(BROKER);
    AMQFrame header(headerBody);
    header.setBof(false);
    header.setEof(false);
    header.setBos(true);
    header.setEos(true);
    AMQContentBody data;
    qpid::amqp_0_10::MapCodec::encode(request, data.getData());
    AMQFrame content(data);
    content.setBof(false);
    content.setEof(true);
    content.setBos(true);
    content.setEos(true);
    sessionHandler.out->handle(method);
    sessionHandler.out->handle(header);
    sessionHandler.out->handle(content);
}
} // namespace

WiringReplicator::~WiringReplicator() {}

WiringReplicator::WiringReplicator(const boost::shared_ptr<Link>& l)
    : Exchange(QPID_WIRING_REPLICATOR), broker(*l->getBroker()), link(l)
{
    QPID_LOG(debug, "HA: Starting replication from " <<
             link->getTransport() << ":" << link->getHost() << ":" << link->getPort());
    broker.getLinks().declare(
        link->getHost(), link->getPort(),
        false,              // durable
        QPID_WIRING_REPLICATOR, // src
        QPID_WIRING_REPLICATOR, // dest
        "",                 // key
        false,              // isQueue
        false,              // isLocal
        "",                 // id/tag
        "",                 // excludes
        false,              // dynamic
        0,                  // sync?
        boost::bind(&WiringReplicator::initializeBridge, this, _1, _2)
    );
}

// This is called in the connection IO thread when the bridge is started.
void WiringReplicator::initializeBridge(Bridge& bridge, SessionHandler& sessionHandler) {
    framing::AMQP_ServerProxy peer(sessionHandler.out);
    string queueName = bridge.getQueueName();
    const qmf::org::apache::qpid::broker::ArgsLinkBridge& args(bridge.getArgs());

    //declare and bind an event queue
    peer.getQueue().declare(queueName, "", false, false, true, true, FieldTable());
    peer.getExchange().bind(queueName, QMF_DEFAULT_TOPIC, AGENT_IND_EVENT_ORG_APACHE_QPID_BROKER, FieldTable());
    //subscribe to the queue
    peer.getMessage().subscribe(queueName, args.i_dest, 1, 0, false, "", 0, FieldTable());
    peer.getMessage().flow(args.i_dest, 0, 0xFFFFFFFF);
    peer.getMessage().flow(args.i_dest, 1, 0xFFFFFFFF);

    //issue a query request for queues and another for exchanges using event queue as the reply-to address
    sendQuery(QUEUE, queueName, sessionHandler);
    sendQuery(EXCHANGE, queueName, sessionHandler);
    sendQuery(BINDING, queueName, sessionHandler);
    QPID_LOG(debug, "HA: Activated wiring replicator")
}

void WiringReplicator::route(Deliverable& msg, const string& /*key*/, const framing::FieldTable* headers) {
    Variant::List list;
    try {
        if (!isQMFv2(msg.getMessage()) || !headers)
            throw Exception("Unexpected message, not QMF2 event or query response.");
        // decode as list
        string content = msg.getMessage().getFrames().getContent();
        amqp_0_10::ListCodec::decode(content, list);

        if (headers->getAsString(QMF_CONTENT) == EVENT) {
            for (Variant::List::iterator i = list.begin(); i != list.end(); ++i) {
                Variant::Map& map = i->asMap();
                Variant::Map& schema = map[SCHEMA_ID].asMap();
                Variant::Map& values = map[VALUES].asMap();
                QPID_LOG(trace, "HA: Configuration event: schema=" << schema << " values=" << values);
                if      (match<EventQueueDeclare>(schema)) doEventQueueDeclare(values);
                else if (match<EventQueueDelete>(schema)) doEventQueueDelete(values);
                else if (match<EventExchangeDeclare>(schema)) doEventExchangeDeclare(values);
                else if (match<EventExchangeDelete>(schema)) doEventExchangeDelete(values);
                else if (match<EventBind>(schema)) doEventBind(values);
                // FIXME aconway 2011-11-21: handle unbind & all other events.
                else if (match<EventSubscribe>(schema)) {} // Deliberately ignored.
                else throw(Exception(QPID_MSG("WiringReplicator received unexpected event, schema=" << schema)));
            }
        } else if (headers->getAsString(QMF_OPCODE) == QUERY_RESPONSE) {
            for (Variant::List::iterator i = list.begin(); i != list.end(); ++i) {
                string type = i->asMap()[SCHEMA_ID].asMap()[CLASS_NAME];
                Variant::Map& values = i->asMap()[VALUES].asMap();
                framing::FieldTable args;
                amqp_0_10::translate(values[ARGUMENTS].asMap(), args);
                QPID_LOG(trace, "HA: Configuration response type=" << type << " values=" << values);
                if      (type == QUEUE) doResponseQueue(values);
                else if (type == EXCHANGE) doResponseExchange(values);
                else if (type == BINDING) doResponseBind(values);
                else throw Exception(QPID_MSG("HA: Unexpected response type: " << type));
            }
        } else {
            QPID_LOG(warning, QPID_MSG("HA: Expecting remote configuration message, got: " << *headers));
        }
    } catch (const std::exception& e) {
        QPID_LOG(warning, "HA: Error replicating configuration: " << e.what());
        QPID_LOG(debug, "HA: Error processing configuration message: " << list);
    }
}

void WiringReplicator::doEventQueueDeclare(Variant::Map& values) {
    string name = values[QNAME].asString();
    Variant::Map argsMap = values[ARGS].asMap();
    if (values[DISP] == CREATED && replicateLevel(argsMap)) {
         framing::FieldTable args;
        amqp_0_10::translate(argsMap, args);

        QPID_LOG(debug, "HA: Creating queue from event " << name);
       std::pair<boost::shared_ptr<Queue>, bool> result =
            broker.createQueue(
                name,
                values[DURABLE].asBool(),
                values[AUTODEL].asBool(),
                0 /*i.e. no owner regardless of exclusivity on master*/,
                values[ALTEX].asString(),
                args,
                values[USER].asString(),
                values[RHOST].asString());
        if (result.second) {
            // FIXME aconway 2011-11-22: should delete old queue and
            // re-create from event.
            // Events are always up to date, whereas responses may be
            // out of date.
            QPID_LOG(debug, "HA: New queue replica " << name);
            startQueueReplicator(result.first);
        } else {
            QPID_LOG(warning, "HA: Replicated queue " << name << " already exists");
        }
    }
}

void WiringReplicator::doEventQueueDelete(Variant::Map& values) {
    string name = values[QNAME].asString();
    boost::shared_ptr<Queue> queue = broker.getQueues().find(name);
    if (queue && replicateLevel(queue->getSettings())) {
        QPID_LOG(debug, "HA: Deleting queue from event: " << name);
        broker.deleteQueue(
            name,
            values[USER].asString(),
            values[RHOST].asString());
    }
}

void WiringReplicator::doEventExchangeDeclare(Variant::Map& values) {
    Variant::Map argsMap(values[ARGS].asMap());
    if (values[DISP] == CREATED && replicateLevel(argsMap)) {
        string name = values[EXNAME].asString();
        framing::FieldTable args;
        amqp_0_10::translate(argsMap, args);
        QPID_LOG(debug, "HA: New exchange replica " << name);
        if (!broker.createExchange(
                name,
                values[EXTYPE].asString(),
                values[DURABLE].asBool(),
                values[ALTEX].asString(),
                args,
                values[USER].asString(),
                values[RHOST].asString()).second) {
            // FIXME aconway 2011-11-22: should delete pre-exisitng exchange
            // and re-create from event. See comment in doEventQueueDeclare.
            QPID_LOG(warning, "HA: Replicated exchange " << name << " already exists");
        }
    }
}

void WiringReplicator::doEventExchangeDelete(Variant::Map& values) {
    string name = values[EXNAME].asString();
    try {
        boost::shared_ptr<Exchange> exchange = broker.getExchanges().get(name);
        if (exchange && replicateLevel(exchange->getArgs())) {
            QPID_LOG(debug, "HA: Deleting exchange:" << name);
            broker.deleteExchange(
                name,
                values[USER].asString(),
                values[RHOST].asString());
        }
    } catch (const framing::NotFoundException&) {}
}

void WiringReplicator::doEventBind(Variant::Map& values) {
    try {
        boost::shared_ptr<Exchange> exchange = broker.getExchanges().get(values[EXNAME].asString());
        boost::shared_ptr<Queue> queue = broker.getQueues().find(values[QNAME].asString());
        // We only replicated a binds for a replicated queue to replicated exchange.
        if (replicateLevel(exchange->getArgs()) && replicateLevel(queue->getSettings())) {
            framing::FieldTable args;
            amqp_0_10::translate(values[ARGS].asMap(), args);
            string key = values[KEY].asString();
            QPID_LOG(debug, "HA: Replicated binding exchange=" << exchange->getName()
                     << " queue=" << queue->getName()
                     << " key=" << key);
            exchange->bind(queue, key, &args);
        }
    } catch (const framing::NotFoundException&) {} // Ignore unreplicated queue or exchange.
}

void WiringReplicator::doResponseQueue(Variant::Map& values) {
    // FIXME aconway 2011-11-22: more flexible ways & defaults to indicate replication
    Variant::Map argsMap(values[ARGUMENTS].asMap());
    if (!replicateLevel(argsMap)) return;
    framing::FieldTable args;
    amqp_0_10::translate(argsMap, args);
    string name(values[NAME].asString());
    std::pair<boost::shared_ptr<Queue>, bool> result =
        broker.createQueue(
            name,
            values[DURABLE].asBool(),
            values[AUTODELETE].asBool(),
            0 /*i.e. no owner regardless of exclusivity on master*/,
            ""/*TODO: need to include alternate-exchange*/,
            args,
            ""/*TODO: who is the user?*/,
            ""/*TODO: what should we use as connection id?*/);
    if (result.second) {
        QPID_LOG(debug, "HA: New queue replica: " << values[NAME] << " (in catch-up)");
        startQueueReplicator(result.first);
    } else {
        // FIXME aconway 2011-11-22: Normal to find queue already
        // exists if we're failing over.
        QPID_LOG(warning, "HA: Replicated queue " << values[NAME] << " already exists (in catch-up)");
    }
}

void WiringReplicator::doResponseExchange(Variant::Map& values) {
    Variant::Map argsMap(values[ARGUMENTS].asMap());
    if (!replicateLevel(argsMap)) return;
    framing::FieldTable args;
    amqp_0_10::translate(argsMap, args);
    QPID_LOG(debug, "HA: New exchange replica " << values[NAME] << " (in catch-up)");
    if (!broker.createExchange(
            values[NAME].asString(),
            values[TYPE].asString(),
            values[DURABLE].asBool(),
            ""/*TODO: need to include alternate-exchange*/,
            args,
            ""/*TODO: who is the user?*/,
            ""/*TODO: what should we use as connection id?*/).second) {
        QPID_LOG(warning, "HA: Replicated exchange " << values[QNAME] << " already exists (in catch-up)");
    }
}

namespace {
const std::string QUEUE_REF_PREFIX("org.apache.qpid.broker:queue:");
const std::string EXCHANGE_REF_PREFIX("org.apache.qpid.broker:exchange:");

std::string getRefName(const std::string& prefix, const Variant& ref) {
    Variant::Map map(ref.asMap());
    Variant::Map::const_iterator i = map.find(OBJECT_NAME);
    if (i == map.end())
        throw Exception(QPID_MSG("Replicator: invalid object reference: " << ref));
    const std::string name = i->second.asString();
    if (name.compare(0, prefix.size(), prefix) != 0)
        throw Exception(QPID_MSG("Replicator: unexpected reference prefix: " << name));
    std::string ret = name.substr(prefix.size());
    return ret;
}

const std::string EXCHANGE_REF("exchangeRef");
const std::string QUEUE_REF("queueRef");

} // namespace

void WiringReplicator::doResponseBind(Variant::Map& values) {
    try {
        std::string exName = getRefName(EXCHANGE_REF_PREFIX, values[EXCHANGE_REF]);
        std::string qName = getRefName(QUEUE_REF_PREFIX, values[QUEUE_REF]);
        boost::shared_ptr<Exchange> exchange = broker.getExchanges().get(exName);
        boost::shared_ptr<Queue> queue = broker.getQueues().find(qName);
        // FIXME aconway 2011-11-24: more flexible configuration for binding replication.

        // Automatically replicate exchange if queue and exchange are replicated
        if (exchange && replicateLevel(exchange->getArgs()) &&
            queue && replicateLevel(queue->getSettings()))
        {
            framing::FieldTable args;
            amqp_0_10::translate(values[ARGUMENTS].asMap(), args);
            string key = values[KEY].asString();
            QPID_LOG(debug, "HA: Replicated binding exchange=" << exchange->getName()
                     << " queue=" << queue->getName()
                     << " key=" << key);
            exchange->bind(queue, key, &args);
        }
    } catch (const framing::NotFoundException& e) {} // Ignore unreplicated queue or exchange.
}

void WiringReplicator::startQueueReplicator(const boost::shared_ptr<Queue>& queue) {
    // FIXME aconway 2011-11-28: also need to remove these when queue is destroyed.
    if (replicateLevel(queue->getSettings()) == RL_ALL) {
        boost::shared_ptr<QueueReplicator> qr(new QueueReplicator(queue, link));
        broker.getExchanges().registerExchange(qr);
    }
}

bool WiringReplicator::bind(boost::shared_ptr<Queue>, const string&, const framing::FieldTable*) { return false; }
bool WiringReplicator::unbind(boost::shared_ptr<Queue>, const string&, const framing::FieldTable*) { return false; }
bool WiringReplicator::isBound(boost::shared_ptr<Queue>, const string* const, const framing::FieldTable* const) { return false; }

string WiringReplicator::getType() const { return QPID_WIRING_REPLICATOR; }

}} // namespace broker