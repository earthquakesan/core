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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.MessageProperties;

public abstract class AbstractDataGenerator extends AbstractPlatformConnectorComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDataGenerator.class);

    private Semaphore startDataGenMutex = new Semaphore(0);
    private int generatorId;
    private int numberOfGenerators;
    protected RabbitQueue dataGen2TaskGenQueue;
    protected RabbitQueue dataGen2SystemQueue;
    
    public AbstractDataGenerator() {
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

        dataGen2TaskGenQueue = createDefaultRabbitQueue(
                generateSessionQueueName(Constants.DATA_GEN_2_TASK_GEN_QUEUE_NAME));
        dataGen2SystemQueue = createDefaultRabbitQueue(
                generateSessionQueueName(Constants.DATA_GEN_2_SYSTEM_QUEUE_NAME));
    }

    @Override
    public void run() throws Exception {
        sendToCmdQueue(Commands.DATA_GENERATOR_READY_SIGNAL);
        // Wait for the start message
        startDataGenMutex.acquire();

        generateData();

        // Unfortunately, we have to wait until all messages are consumed
        try {
            while (dataGen2SystemQueue.messageCount() > 0) {
                Thread.sleep(500);
            }
        } catch (AlreadyClosedException e) {
            LOGGER.info("The queue to the system is already closed. Assuming that all messages have been consumed.");
        } catch (Exception e) {
            LOGGER.warn("Exception while trying to check whether all messages have been consumed. It will be ignored.",
                    e);
        }
        try {
            while (dataGen2TaskGenQueue.messageCount() > 0) {
                Thread.sleep(500);
            }
        } catch (AlreadyClosedException e) {
            LOGGER.info("The queue to the system is already closed. Assuming that all messages have been consumed.");
        } catch (Exception e) {
            LOGGER.warn("Exception while trying to check whether all messages have been consumed. It will be ignored.",
                    e);
        }
    }

    protected abstract void generateData() throws Exception;

    @Override
    public void receiveCommand(byte command, byte[] data) {
        // If this is the signal to start the data generation
        if (command == Commands.DATA_GENERATOR_START_SIGNAL) {
            // release the mutex
            startDataGenMutex.release();
        }
    }

    protected void sendDataToTaskGenerator(byte[] data) throws IOException {
        dataGen2TaskGenQueue.channel.basicPublish("", dataGen2TaskGenQueue.name, MessageProperties.PERSISTENT_BASIC,
                data);
    }

    protected void sendDataToSystemAdapter(byte[] data) throws IOException {
        dataGen2SystemQueue.channel.basicPublish("", dataGen2SystemQueue.name, MessageProperties.PERSISTENT_BASIC,
                data);
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
        IOUtils.closeQuietly(dataGen2SystemQueue);
        super.close();
    }
}
