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
package org.apache.qpid.client;

import org.apache.qpid.common.QpidProperties;

import javax.jms.ConnectionMetaData;
import javax.jms.JMSException;
import java.util.Enumeration;

public class QpidConnectionMetaData implements ConnectionMetaData
{

    private AMQConnection con;

    QpidConnectionMetaData(AMQConnection conn)
    {
        this.con = conn;
    }

    public int getJMSMajorVersion() throws JMSException
    {
        return 1;
    }

    public int getJMSMinorVersion() throws JMSException
    {
        return 1;
    }

    public String getJMSProviderName() throws JMSException
    {
        return "Apache " + QpidProperties.getProductName();
    }

    public String getJMSVersion() throws JMSException
    {
        return "1.1";
    }

    public Enumeration getJMSXPropertyNames() throws JMSException
    {
        return CustomJMSXProperty.asEnumeration();
    }

    public int getProviderMajorVersion() throws JMSException
    {
        return con.getProtocolVersion().getMajorVersion();
    }

    public int getProviderMinorVersion() throws JMSException
    {
        return con.getProtocolVersion().getMinorVersion();
    }

    public String getProviderVersion() throws JMSException
    {
        return QpidProperties.getProductName() + " (Client: [" + getClientVersion() + "] ; Broker [" + getBrokerVersion() + "] ; Protocol: [ "
               + getProtocolVersion() + "] )";
    }

    private String getProtocolVersion()
    {
        return con.getProtocolVersion().toString();
    }

    public String getBrokerVersion()
    {
        // TODO - get broker version
        return "<unkown>";
    }

    public String getClientVersion()
    {
        return QpidProperties.getBuildVersion();
    }


}
