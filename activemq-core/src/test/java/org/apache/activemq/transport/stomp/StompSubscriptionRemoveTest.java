/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.stomp;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 */
public class StompSubscriptionRemoveTest extends TestCase {
    private static final Logger LOG = LoggerFactory.getLogger(StompSubscriptionRemoveTest.class);
    private static final String COMMAND_MESSAGE = "MESSAGE";
    private static final String HEADER_MESSAGE_ID = "message-id";

    private StompConnection stompConnection = new StompConnection();

    public void testRemoveSubscriber() throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);

        broker.addConnector("stomp://localhost:0").setName("Stomp");
        broker.addConnector("tcp://localhost:0").setName("Default");
        broker.start();

        final String stompUri = broker.getConnectorByName("Stomp").getPublishableConnectString();
        final int stompPort = new URI(stompUri).getPort();
        final String openwireUri = broker.getConnectorByName("Default").getPublishableConnectString();

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(openwireUri);
        Connection connection = factory.createConnection();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = session.createProducer(new ActiveMQQueue(getDestinationName()));
        Message message = session.createTextMessage("Testas");
        for (int idx = 0; idx < 2000; ++idx) {
            producer.send(message);
            LOG.debug("Sending: " + idx);
        }
        producer.close();
        session.close();
        connection.close();

        stompConnection.open(new Socket("localhost", stompPort));

        String connectFrame = "CONNECT\n" + "login: brianm\n" + "passcode: wombats\n\n" + Stomp.NULL;
        stompConnection.sendFrame(connectFrame);

        stompConnection.receiveFrame();
        String frame = "SUBSCRIBE\n" + "destination:/queue/" + getDestinationName() + "\n" + "ack:client\n\n" + Stomp.NULL;
        stompConnection.sendFrame(frame);

        int messagesCount = 0;
        int count = 0;
        while (count < 2) {
            String receiveFrame = stompConnection.receiveFrame();
            LOG.debug("Received: " + receiveFrame);
            assertEquals("Unexpected frame received", COMMAND_MESSAGE, getCommand(receiveFrame));
            String messageId = getHeaderValue(receiveFrame, HEADER_MESSAGE_ID);
            String ackmessage = "ACK\n" + HEADER_MESSAGE_ID + ":" + messageId + "\n\n"+ Stomp.NULL;
            stompConnection.sendFrame(ackmessage);
            // Thread.sleep(1000);
            ++messagesCount;
            ++count;
        }

        stompConnection.sendFrame("DISCONNECT\n\n");
        Thread.sleep(1000);
        stompConnection.close();

        stompConnection.open(new Socket("localhost", stompPort));

        connectFrame = "CONNECT\n" + "login: brianm\n" + "passcode: wombats\n\n" + Stomp.NULL;
        stompConnection.sendFrame(connectFrame);

        stompConnection.receiveFrame();

        frame = "SUBSCRIBE\n" + "destination:/queue/" + getDestinationName() + "\n" + "ack:client\n\n" + Stomp.NULL;
        stompConnection.sendFrame(frame);
        try {
            while (count != 2000) {
                String receiveFrame = stompConnection.receiveFrame();
                LOG.debug("Received: " + receiveFrame);
                assertEquals("Unexpected frame received", COMMAND_MESSAGE, getCommand(receiveFrame));
                String messageId = getHeaderValue(receiveFrame, HEADER_MESSAGE_ID);
                String ackmessage = "ACK\n" + HEADER_MESSAGE_ID + ":" + messageId.trim() + "\n\n" + Stomp.NULL;
                stompConnection.sendFrame(ackmessage);
                // Thread.sleep(1000);
                ++messagesCount;
                ++count;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        stompConnection.sendFrame("DISCONNECT\n\n");
        stompConnection.close();
        broker.stop();

        LOG.info("Total messages received: " + messagesCount);
        assertTrue("Messages received after connection loss: " + messagesCount, messagesCount >= 2000);

        // The first ack messages has no chance complete, so we receiving more
        // messages

        // Don't know how to list subscriptions for the broker. Currently you
        // can check using JMX console. You'll see
        // Subscription without any connections
    }

    protected String getDestinationName() {
        return getClass().getName() + "." + getName();
    }

    // These two methods could move to a utility class
    protected String getCommand(String frame) {
        return frame.substring(0, frame.indexOf('\n') + 1).trim();
    }

    protected String getHeaderValue(String frame, String header) throws IOException {
        DataInput input = new DataInputStream(new ByteArrayInputStream(frame.getBytes()));
        String line;
        for (int idx = 0; /* forever, sort of */; ++idx) {
            line = input.readLine();
            if (line == null) {
                // end of message, no headers
                return null;
            }
            line = line.trim();
            if (line.length() == 0) {
                // start body, no headers from here on
                return null;
            }
            if (idx > 0) { // Ignore command line
                int pos = line.indexOf(':');
                if (header.equals(line.substring(0, pos))) {
                    return line.substring(pos + 1).trim();
                }
            }
        }
    }
}
