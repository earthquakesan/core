package org.hobbit.core.components;

import org.hobbit.core.components.stream.StreamingGeneratedDataReceivingComponent;

/**
 * This interface is implemented by components that want to receive data that is
 * sent by a data generator component.
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public interface GeneratedDataReceivingComponent extends StreamingGeneratedDataReceivingComponent {

    /**
     * This method is called if data is received from a data generator.
     * 
     * @param data
     *            the data received from a data generator
     */
    public void receiveGeneratedData(byte[] data);

}
