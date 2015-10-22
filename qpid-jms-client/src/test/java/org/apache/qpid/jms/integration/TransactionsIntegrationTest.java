/*
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
 */
package org.apache.qpid.jms.integration;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TransactionRolledBackException;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsPrefetchPolicy;
import org.apache.qpid.jms.test.QpidJmsTestCase;
import org.apache.qpid.jms.test.testpeer.TestAmqpPeer;
import org.apache.qpid.jms.test.testpeer.describedtypes.Accepted;
import org.apache.qpid.jms.test.testpeer.describedtypes.Error;
import org.apache.qpid.jms.test.testpeer.describedtypes.Modified;
import org.apache.qpid.jms.test.testpeer.describedtypes.Rejected;
import org.apache.qpid.jms.test.testpeer.describedtypes.Released;
import org.apache.qpid.jms.test.testpeer.describedtypes.TransactionalState;
import org.apache.qpid.jms.test.testpeer.describedtypes.sections.AmqpValueDescribedType;
import org.apache.qpid.jms.test.testpeer.matchers.AcceptedMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.ModifiedMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.ReleasedMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.SourceMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.TransactionalStateMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageAnnotationsSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageHeaderSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.TransferPayloadCompositeMatcher;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.DescribedType;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for behavior of Transacted Session operations.
 */
public class TransactionsIntegrationTest extends QpidJmsTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionsIntegrationTest.class);

    private final IntegrationTestFixture testFixture = new IntegrationTestFixture();

    @Test(timeout=20000)
    public void testTransactionRolledBackOnSessionClose() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            // Closed session should roll-back the TX with a failed discharge
            testPeer.expectDischarge(txnId, true);
            testPeer.expectEnd();

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

            session.close();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testTransactionCommitFailWithEmptyRejectedDisposition() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher(new MessageAnnotationsSectionMatcher(true));

            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with rejected and settled disposition to indicate the commit failed
            testPeer.expectDischarge(txnId, false, new Rejected());

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            try {
                session.commit();
                fail("Commit operation should have failed.");
            } catch (TransactionRolledBackException jmsTxRb) {
            }

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testProducedMessagesAfterCommitOfSentMessagesFails() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher(new MessageAnnotationsSectionMatcher(true));

            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with rejected and settled disposition to indicate the commit failed
            Rejected commitFailure = new Rejected(new Error(Symbol.valueOf("failed"), "Unknown error"));
            testPeer.expectDischarge(txnId, false, commitFailure);

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            try {
                session.commit();
                fail("Commit operation should have failed.");
            } catch (TransactionRolledBackException jmsTxRb) {
            }

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testProducedMessagesAfterRollbackSentMessagesFails() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher(new MessageAnnotationsSectionMatcher(true));

            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with rejected and settled disposition to indicate the rollback failed
            Rejected commitFailure = new Rejected(new Error(Symbol.valueOf("failed"), "Unknown error"));
            testPeer.expectDischarge(txnId, true, commitFailure);

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            try {
                session.rollback();
                fail("Rollback operation should have failed.");
            } catch (JMSException jmsex) {
            }

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testCommitTransactedSessionWithConsumerReceivingAllMessages() throws Exception {
        doCommitTransactedSessionWithConsumerTestImpl(1, 1, false);
    }

    @Test(timeout=20000)
    public void testCommitTransactedSessionWithConsumerReceivingSomeMessages() throws Exception {
        doCommitTransactedSessionWithConsumerTestImpl(5, 2, false);
    }

    @Test(timeout=20000)
    public void testCommitTransactedSessionWithConsumerReceivingAllMessagesAndClose() throws Exception {
        doCommitTransactedSessionWithConsumerTestImpl(1, 1, true);
    }

    private void doCommitTransactedSessionWithConsumerTestImpl(int transferCount, int consumeCount, boolean closeConsumer) throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, new AmqpValueDescribedType("content"), transferCount);

            for (int i = 1; i <= consumeCount; i++) {
                // Then expect an *settled* TransactionalState disposition for each message once received by the consumer
                TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
                stateMatcher.withTxnId(equalTo(txnId));
                stateMatcher.withOutcome(new AcceptedMatcher());

                testPeer.expectDisposition(true, stateMatcher);
            }

            MessageConsumer messageConsumer = session.createConsumer(queue);

            for (int i = 1; i <= consumeCount; i++) {
                Message receivedMessage = messageConsumer.receive(3000);

                assertNotNull(receivedMessage);
                assertTrue(receivedMessage instanceof TextMessage);
            }

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the commit succeeded
            testPeer.expectDischarge(txnId, false);

            // Expect the consumer to close now
            if (closeConsumer) {
                testPeer.expectDetach(true, true, true);
                messageConsumer.close();
            }

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            session.commit();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 20000)
    public void testConsumerWithNoMessageCanCloseBeforeCommit() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlow();
            testPeer.expectLinkFlow(true, true, equalTo(UnsignedInteger.valueOf(JmsPrefetchPolicy.DEFAULT_QUEUE_PREFETCH)));
            testPeer.expectLinkFlow(false, false, equalTo(UnsignedInteger.valueOf(JmsPrefetchPolicy.DEFAULT_QUEUE_PREFETCH)));
            testPeer.expectDetach(true, true, true);

            MessageConsumer messageConsumer = session.createConsumer(queue);
            assertNull(messageConsumer.receiveNoWait());

            messageConsumer.close();

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the commit succeeded
            testPeer.expectDischarge(txnId, false);

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            session.commit();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 20000)
    public void testConsumerWithNoMessageCanCloseBeforeRollback() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlow();
            testPeer.expectLinkFlow(true, true, equalTo(UnsignedInteger.valueOf(JmsPrefetchPolicy.DEFAULT_QUEUE_PREFETCH)));
            testPeer.expectLinkFlow(false, false, equalTo(UnsignedInteger.valueOf(JmsPrefetchPolicy.DEFAULT_QUEUE_PREFETCH)));
            testPeer.expectDetach(true, true, true);

            MessageConsumer messageConsumer = session.createConsumer(queue);
            assertNull(messageConsumer.receiveNoWait());

            messageConsumer.close();

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the commit succeeded
            testPeer.expectDischarge(txnId, true);

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectDeclare(txnId);

            session.rollback();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testProducedMessagesOnTransactedSessionCarryTxnId() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which was sent under the current transaction. Check it carries
            // TransactionalState with the above txnId but has no outcome. Respond with a
            // TransactionalState with Accepted outcome.
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher(new MessageAnnotationsSectionMatcher(true));

            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testRollbackTransactedSessionWithConsumerReceivingAllMessages() throws Exception {
        doRollbackTransactedSessionWithConsumerTestImpl(1, 1, false);
    }

    @Test(timeout=20000)
    public void testRollbackTransactedSessionWithConsumerReceivingAllMessagesThenCloses() throws Exception {
        doRollbackTransactedSessionWithConsumerTestImpl(1, 1, true);
    }

    @Test(timeout=20000)
    public void testRollbackTransactedSessionWithConsumerReceivingSomeMessages() throws Exception {
        doRollbackTransactedSessionWithConsumerTestImpl(5, 2, false);
    }

    private void doRollbackTransactedSessionWithConsumerTestImpl(int transferCount, int consumeCount, boolean closeConsumer) throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, new AmqpValueDescribedType("content"), transferCount);

            for (int i = 1; i <= consumeCount; i++) {
                // Then expect a *settled* TransactionalState disposition for each message once received by the consumer
                TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
                stateMatcher.withTxnId(equalTo(txnId));
                stateMatcher.withOutcome(new AcceptedMatcher());

                testPeer.expectDisposition(true, stateMatcher);
            }

            MessageConsumer messageConsumer = session.createConsumer(queue);

            for (int i = 1; i <= consumeCount; i++) {
                Message receivedMessage = messageConsumer.receive(3000);

                assertNotNull(receivedMessage);
                assertTrue(receivedMessage instanceof TextMessage);
            }

            // Expect the consumer to be 'stopped' prior to rollback by issuing a 'drain'
            testPeer.expectLinkFlow(true, true, greaterThan(UnsignedInteger.ZERO));

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the rollback succeeded
            testPeer.expectDischarge(txnId, true);

            if (closeConsumer) {
                testPeer.expectDetach(true, true, true);
                messageConsumer.close();
            }

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            // Expect the messages that were not consumed to be released
            int unconsumed = transferCount - consumeCount;
            for (int i = 1; i <= unconsumed; i++) {
                testPeer.expectDisposition(true, new ReleasedMatcher());
            }

            if (!closeConsumer) {
                // Expect the consumer to be 'started' again as rollback completes
                testPeer.expectLinkFlow(false, false, greaterThan(UnsignedInteger.ZERO));
            }

            session.rollback();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testRollbackTransactedSessionWithPrefetchFullBeforeStoppingConsumer() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            int messageCount = 5;
            ((JmsConnection) connection).getPrefetchPolicy().setAll(messageCount);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a consumer and fill the prefetch with messages, which we wont consume any of
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, new AmqpValueDescribedType("content"), messageCount);

            session.createConsumer(queue);

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which provoked creating the transaction
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher( new MessageAnnotationsSectionMatcher(true));
            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            // The consumer will be 'stopped' prior to rollback, however we will NOT send a 'drain' Flow
            // frame as we have manipulated that all the credit was already used, i.e. it already stopped.

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the rollback succeeded
            testPeer.expectDischarge(txnId, true);

            // Now expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            // Expect the messages that were not consumed to be released
            for (int i = 1; i <= messageCount; i++) {
                testPeer.expectDisposition(true, new ReleasedMatcher());
            }

            // Expect the consumer to be 'started' again as rollback completes
            testPeer.expectLinkFlow(false, false, equalTo(UnsignedInteger.valueOf(messageCount)));

            session.rollback();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testRollbackTransactedSessionWithPrefetchFullyUtilisedByDrainWhenStoppingConsumer() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            int messageCount = 5;
            ((JmsConnection) connection).getPrefetchPolicy().setAll(messageCount);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a consumer, expect it to flow credit, but don't send it any messages
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlow(false, false, equalTo(UnsignedInteger.valueOf(messageCount)));

            session.createConsumer(queue);

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            // Expect the message which provoked creating the transaction
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(new MessageHeaderSectionMatcher(true));
            messageMatcher.setMessageAnnotationsMatcher( new MessageAnnotationsSectionMatcher(true));

            TransactionalStateMatcher stateMatcher = new TransactionalStateMatcher();
            stateMatcher.withTxnId(equalTo(txnId));
            stateMatcher.withOutcome(nullValue());

            TransactionalState txState = new TransactionalState();
            txState.setTxnId(txnId);
            txState.setOutcome(new Accepted());

            testPeer.expectTransfer(messageMatcher, stateMatcher, false, txState, true);

            producer.send(session.createMessage());

            // Expect the consumer to be 'stopped' prior to rollback by issuing a 'drain' Flow.
            // Action the drain by filling the prefetch (which is equivalent to this having happened while
            // the Flow was in flight to the peer), and then DONT send a flow frame back to the client
            // as it can tell from the messages that all the credit has been used.
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, new AmqpValueDescribedType("content"),
                                                       messageCount, true, false, equalTo(UnsignedInteger.valueOf(messageCount)), 1, false);

            // Expect an unsettled 'discharge' transfer to the txn coordinator containing the txnId,
            // and reply with accepted and settled disposition to indicate the rollback succeeded
            testPeer.expectDischarge(txnId, true);

            // Then expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            // Expect the messages that were not consumed to be released
            for (int i = 1; i <= messageCount; i++) {
                testPeer.expectDisposition(true, new ReleasedMatcher());
            }

            // Expect the consumer to be 'started' again as rollback completes
            testPeer.expectLinkFlow(false, false, equalTo(UnsignedInteger.valueOf(messageCount)));

            session.rollback();

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testDefaultOutcomeIsModifiedForConsumerSourceOnTransactedSession() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            String queueName = "myQueue";
            Queue queue = session.createQueue(queueName);

            SourceMatcher sourceMatcher = new SourceMatcher();
            sourceMatcher.withAddress(equalTo(queueName));
            sourceMatcher.withDynamic(equalTo(false));
            sourceMatcher.withOutcomes(arrayContaining(Accepted.DESCRIPTOR_SYMBOL, Rejected.DESCRIPTOR_SYMBOL, Released.DESCRIPTOR_SYMBOL, Modified.DESCRIPTOR_SYMBOL));
            ModifiedMatcher outcomeMatcher = new ModifiedMatcher().withDeliveryFailed(equalTo(true)).withUndeliverableHere(nullValue());
            sourceMatcher.withDefaultOutcome(outcomeMatcher);

            testPeer.expectReceiverAttach(notNullValue(), sourceMatcher);
            testPeer.expectLinkFlow();

            session.createConsumer(queue);

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testRollbackErrorCoordinatorClosedOnCommit() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);
            testPeer.remotelyCloseLastCoordinatorLinkOnDischarge(txnId, false);
            testPeer.expectCoordinatorAttach();
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

            try {
                session.commit();
                fail("Transaction should have rolled back");
            } catch (TransactionRolledBackException ex) {
                LOG.info("Caught expected TransactionRolledBackException");
            }

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testJMSErrorCoordinatorClosedOnRollback() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);
            testPeer.remotelyCloseLastCoordinatorLinkOnDischarge(txnId, true);
            testPeer.expectCoordinatorAttach();
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

            try {
                session.rollback();
                fail("Transaction should have rolled back");
            } catch (JMSException ex) {
                LOG.info("Caught expected JMSException");
            }

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testSendAfterCoordinatorLinkClosedDuringTX() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a producer to use in provoking creation of the AMQP transaction
            testPeer.expectSenderAttach();

            // Close the link, the messages should now just get dropped on the floor.
            testPeer.remotelyCloseLastCoordinatorLink();

            MessageProducer producer = session.createProducer(queue);

            testPeer.waitForAllHandlersToComplete(2000);

            producer.send(session.createMessage());

            // Expect that a new link will be created in order to start the next TX.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectCoordinatorAttach();
            testPeer.expectDeclare(txnId);

            try {
                session.commit();
                fail("Commit operation should have failed.");
            } catch (TransactionRolledBackException jmsTxRb) {
            }

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout=20000)
    public void testReceiveAfterCoordinatorLinkClosedDuringTX() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin();
            testPeer.expectCoordinatorAttach();

            // First expect an unsettled 'declare' transfer to the txn coordinator, and
            // reply with a Declared disposition state containing the txnId.
            Binary txnId = new Binary(new byte[]{ (byte) 5, (byte) 6, (byte) 7, (byte) 8});
            testPeer.expectDeclare(txnId);

            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue("myQueue");

            // Create a consumer and send it an initial message for receive to process.
            DescribedType amqpValueNullContent = new AmqpValueDescribedType(null);
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, amqpValueNullContent);

            // Close the link, the messages should now just get dropped on the floor.
            testPeer.remotelyCloseLastCoordinatorLink();

            MessageConsumer consumer = session.createConsumer(queue);

            testPeer.waitForAllHandlersToComplete(2000);

            // receiving the message would normally ack it, since the TX is failed this
            // should not result in a disposition going out.
            Message received = consumer.receive();
            assertNotNull(received);

            // Expect that a new link will be created in order to start the next TX.
            txnId = new Binary(new byte[]{ (byte) 1, (byte) 2, (byte) 3, (byte) 4});
            testPeer.expectCoordinatorAttach();
            testPeer.expectDeclare(txnId);

            try {
                session.commit();
                fail("Commit operation should have failed.");
            } catch (TransactionRolledBackException jmsTxRb) {
            }

            testPeer.waitForAllHandlersToComplete(1000);
        }
    }
}