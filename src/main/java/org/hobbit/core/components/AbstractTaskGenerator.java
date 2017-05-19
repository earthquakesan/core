/**
 * This file is part of core.
 *
 * core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with core.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.hobbit.core.components;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.apache.commons.io.IOUtils;
import org.hobbit.core.Commands;
import org.hobbit.core.Constants;
import org.hobbit.core.data.RabbitQueue;
import org.hobbit.core.rabbit.DataSender;
import org.hobbit.core.rabbit.DataSenderImpl;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.QueueingConsumer.Delivery;

/**
 * This abstract class implements basic functions that can be used to implement
 * a task generator.
 *
 * The following environment variables are expected:
 * <ul>
 * <li>{@link Constants#GENERATOR_ID_KEY}</li>
 * <li>{@link Constants#GENERATOR_COUNT_KEY}</li>
 * </ul>
 *
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public abstract class AbstractTaskGenerator extends AbstractPlatformConnectorComponent
        implements GeneratedDataReceivingComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTaskGenerator.class);

    /**
     * Default value of the {@link #maxParallelProcessedMsgs} attribute.
     */
    private static final int DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES = 1;

    /**
     * Mutex used to wait for the start signal after the component has been
     * started and initialized.
     */
    private Semaphore startTaskGenMutex = new Semaphore(0);
    /**
     * Mutex used to wait for the terminate signal.
     */
    private Semaphore terminateMutex = new Semaphore(0);
    /**
     * Semaphore used to control the number of messages that can be processed in
     * parallel.
     */
    private Semaphore currentlyProcessedMessages;
    /**
     * The id of this generator.
     */
    private int generatorId;
    /**
     * The number of task generators created by the benchmark controller.
     */
    private int numberOfGenerators;
    /**
     * The task id that will be assigned to the next task generated by this
     * generator.
     */
    private long nextTaskId;
    /**
     * The maximum number of incoming messages that are processed in parallel.
     * Additional messages have to wait.
     */
    private final int maxParallelProcessedMsgs;

    protected DataSender sender2System;
    protected DataSender sender2EvalStore;
    protected RabbitQueue dataGen2TaskGenQueue;

    protected QueueingConsumer consumer;
    protected boolean runFlag;

    /**
     * Default constructor creating an {@link AbstractTaskGenerator} processing
     * up to {@link #DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES}=
     * {@value #DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES} messages in parallel.
     */
    public AbstractTaskGenerator() {
        this(DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES);
    }

    /**
     * Constructor setting the maximum number of parallel processed messages.
     * Note that this parameter has to be larger or equal to 1 or the
     * {@link #init()} method will throw an exception. Setting
     * <code>maxParallelProcessedMsgs=1</code> leads to the usage of a
     * {@link QueueingConsumer}.
     *
     * @param maxParallelProcessedMsgs
     *            the number of messaegs that are processed in parallel
     */
    public AbstractTaskGenerator(int maxParallelProcessedMsgs) {
        this.maxParallelProcessedMsgs = maxParallelProcessedMsgs;
        defaultContainerType = Constants.CONTAINER_TYPE_BENCHMARK;
    }

    @Override
    public void init() throws Exception {
        super.init();
        Map<String, String> env = System.getenv();

        if (!env.containsKey(Constants.GENERATOR_ID_KEY)) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_ID_KEY + "\" from the environment. Aborting.");
        }
        try {
            generatorId = Integer.parseInt(env.get(Constants.GENERATOR_ID_KEY));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_ID_KEY + "\" from the environment. Aborting.", e);
        }
        nextTaskId = generatorId;

        if (!env.containsKey(Constants.GENERATOR_COUNT_KEY)) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_COUNT_KEY + "\" from the environment. Aborting.");
        }
        try {
            numberOfGenerators = Integer.parseInt(env.get(Constants.GENERATOR_COUNT_KEY));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_COUNT_KEY + "\" from the environment. Aborting.", e);
        }

        sender2System = DataSenderImpl.builder().queue(this, Constants.TASK_GEN_2_SYSTEM_QUEUE_NAME).build();
        sender2EvalStore = DataSenderImpl.builder().queue(this, Constants.TASK_GEN_2_EVAL_STORAGE_QUEUE_NAME).build();

        dataGen2TaskGenQueue = createDefaultRabbitQueue(
                generateSessionQueueName(Constants.DATA_GEN_2_TASK_GEN_QUEUE_NAME));
        if (maxParallelProcessedMsgs == 1) {
            consumer = new QueueingConsumer(dataGen2TaskGenQueue.channel);
            dataGen2TaskGenQueue.channel.basicConsume(dataGen2TaskGenQueue.name, true, consumer);
        } else if (maxParallelProcessedMsgs > 1) {
            currentlyProcessedMessages = new Semaphore(maxParallelProcessedMsgs);
            @SuppressWarnings("resource")
            GeneratedDataReceivingComponent receiver = this;
            dataGen2TaskGenQueue.channel.basicConsume(dataGen2TaskGenQueue.name, true,
                    new DefaultConsumer(dataGen2TaskGenQueue.channel) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties,
                                byte[] body) throws IOException {
                            // LOGGER.info("Received data " + dataCount);
                            // ++dataCount;
                            try {
                                currentlyProcessedMessages.acquire();
                                try {
                                    receiver.receiveGeneratedData(body);
                                } catch (Exception e) {
                                    LOGGER.error("Got exception while trying to process incoming data.", e);
                                } finally {
                                    currentlyProcessedMessages.release();
                                }
                            } catch (InterruptedException e) {
                                throw new IOException("Interrupted while waiting for mutex.", e);
                            }
                        }
                    });
        } else {
            throw new IllegalArgumentException("The maximum number of messages processed in parallel has to be >=1.");
        }
        dataGen2TaskGenQueue.channel.basicQos(maxParallelProcessedMsgs);
    }

    @Override
    public void run() throws Exception {
        sendToCmdQueue(Commands.TASK_GENERATOR_READY_SIGNAL);
        // Wait for the start message
        startTaskGenMutex.acquire();

        if (maxParallelProcessedMsgs == 1) {
            runFlag = true;
            Delivery delivery = null;
            int count = 0;
            // As long as a) this component should run or b) there are still
            // messages in the queue or c) the last delivery was not empty
            // (i.e., there could be another deliverable waiting)
            while (runFlag || (dataGen2TaskGenQueue.messageCount() > 0) || (delivery != null)) {
                delivery = consumer.nextDelivery(3000);
                if (delivery != null) {
                    generateTask(delivery.getBody());
                    ++count;
                }
            }
            LOGGER.info("Terminating after " + count + " processed messages.");
        } else {
            terminateMutex.acquire();
            // wait until all messages have been read from the queue
            long messageCount = dataGen2TaskGenQueue.messageCount();
            while (messageCount > 0) {
                LOGGER.info("Waiting for remaining data to be processed: " + messageCount);
                Thread.sleep(1000);
                messageCount = dataGen2TaskGenQueue.messageCount();
            }
            // Collect all open mutex counts to make sure that there is no
            // message that is still processed
            // LOGGER.info("Waiting data processing to finish... (" + debugCount
            // + " tasks generated. "
            // + currentlyProcessedMessages.availablePermits() + " are
            // available)");
            LOGGER.info("Waiting data processing to finish... ( {} / {} free permits are available)",
                    currentlyProcessedMessages.availablePermits(), maxParallelProcessedMsgs);
            currentlyProcessedMessages.acquire(maxParallelProcessedMsgs);
        }

        // make sure that all messages have been delivered (otherwise they might
        // be lost)
        sender2System.closeWhenFinished();
        sender2EvalStore.closeWhenFinished();
    }

    @Override
    public void receiveGeneratedData(byte[] data) {
        try {
            generateTask(data);
        } catch (Exception e) {
            LOGGER.error("Exception while generating task.", e);
        }
    }

    /**
     * Generates a task from the given data, sends it to the system, takes the
     * timestamp of the moment at which the message has been sent to the system
     * and sends it together with the expected response to the evaluation
     * storage.
     *
     * @param data
     *            incoming data generated by a data generator
     * @throws Exception
     *             if a sever error occurred
     */
    protected abstract void generateTask(byte[] data) throws Exception;

    /**
     * Generates the next unique ID for a task.
     *
     * @return the next unique task ID
     */
    protected synchronized String getNextTaskId() {
        String taskIdString = Long.toString(nextTaskId);
        nextTaskId += numberOfGenerators;
        return taskIdString;
    }

    @Override
    public void receiveCommand(byte command, byte[] data) {
        // If this is the signal to start the data generation
        if (command == Commands.TASK_GENERATOR_START_SIGNAL) {
            LOGGER.info("Received signal to start.");
            // release the mutex
            startTaskGenMutex.release();
        } else if (command == Commands.DATA_GENERATION_FINISHED) {
            LOGGER.info("Received signal to finish.");
            if (maxParallelProcessedMsgs == 1) {
                runFlag = false;
            } else {
                // release the mutex
                terminateMutex.release();
            }
        }
        super.receiveCommand(command, data);
    }

    /**
     * This method sends the given data and the given timestamp of the task with
     * the given task id to the evaluation storage.
     *
     * @param taskIdString
     *            the id of the task
     * @param timestamp
     *            the timestamp of the moment in which the task has been sent to
     *            the system
     * @param data
     *            the expected response for the task with the given id
     * @throws IOException
     *             if there is an error during the sending
     */
    protected void sendTaskToEvalStorage(String taskIdString, long timestamp, byte[] data) throws IOException {
        sender2EvalStore.sendData(RabbitMQUtils.writeByteArrays(null, new byte[][] { RabbitMQUtils.writeString(taskIdString), data },
                        RabbitMQUtils.writeLong(timestamp)));
    }

    /**
     * Sends the given task with the given task id and data to the system.
     *
     * @param taskIdString
     *            the id of the task
     * @param data
     *            the data of the task
     * @throws IOException
     *             if there is an error during the sending
     */
    protected void sendTaskToSystemAdapter(String taskIdString, byte[] data) throws IOException {
        sender2System.sendData(RabbitMQUtils.writeByteArrays(new byte[][] { RabbitMQUtils.writeString(taskIdString), data }));
    }

    public int getGeneratorId() {
        return generatorId;
    }

    public int getNumberOfGenerators() {
        return numberOfGenerators;
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(dataGen2TaskGenQueue);
        IOUtils.closeQuietly(sender2EvalStore);
        IOUtils.closeQuietly(sender2System);
        super.close();
    }
}
