/* Licensed to the Apache Software Foundation (ASF) under one
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
package org.apache.qpid.test.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.qpid.AMQException;
import org.apache.qpid.client.AMQConnectionFactory;
import org.apache.qpid.client.AMQQueue;
import org.apache.qpid.client.AMQTopic;
import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.jms.BrokerDetails;
import org.apache.qpid.jms.ConnectionURL;
import org.apache.qpid.management.common.mbeans.ConfigurationManagement;
import org.apache.qpid.server.Broker;
import org.apache.qpid.server.BrokerOptions;
import org.apache.qpid.server.ProtocolExclusion;
import org.apache.qpid.server.ProtocolInclusion;
import org.apache.qpid.server.configuration.ServerConfiguration;
import org.apache.qpid.server.protocol.AmqpProtocolVersion;
import org.apache.qpid.server.store.MessageStoreConstants;
import org.apache.qpid.server.store.derby.DerbyMessageStore;
import org.apache.qpid.url.URLSyntaxException;
import org.apache.qpid.util.FileUtils;
import org.apache.qpid.util.LogMonitor;

/**
 * Qpid base class for system testing test cases.
 */
public class QpidBrokerTestCase extends QpidTestCase
{
    public enum BrokerType
    {
        EXTERNAL /** Test case relies on a Broker started independently of the test-suite */,
        INTERNAL /** Test case starts an embedded broker within this JVM */, 
        SPAWNED /** Test case spawns a new broker as a separate process */
    }

    public static final String GUEST_USERNAME = "guest";
    public static final String GUEST_PASSWORD = "guest";

    protected final static String QpidHome = System.getProperty("QPID_HOME");
    protected File _configFile = new File(System.getProperty("broker.config"));
    protected File _logConfigFile = new File(System.getProperty("log4j.configuration"));

    protected static final Logger _logger = Logger.getLogger(QpidBrokerTestCase.class);
    protected static final int LOGMONITOR_TIMEOUT = 5000;

    protected long RECEIVE_TIMEOUT = 1000l;

    private Map<String, String> _propertiesSetForBroker = new HashMap<String, String>();

    private XMLConfiguration _testConfiguration = new XMLConfiguration();
    private XMLConfiguration _testVirtualhosts = new XMLConfiguration();

    protected static final String INDEX = "index";
    protected static final String CONTENT = "content";

    private static final String DEFAULT_INITIAL_CONTEXT = "org.apache.qpid.jndi.PropertiesFileInitialContextFactory";

    static
    {
        String initialContext = System.getProperty(InitialContext.INITIAL_CONTEXT_FACTORY);

        if (initialContext == null || initialContext.length() == 0)
        {
            System.setProperty(InitialContext.INITIAL_CONTEXT_FACTORY, DEFAULT_INITIAL_CONTEXT);
        }
    }

    // system properties
    private static final String TEST_VIRTUALHOSTS = "test.virtualhosts";
    private static final String TEST_CONFIG = "test.config";
    private static final String BROKER_LANGUAGE = "broker.language";
    protected static final String BROKER_TYPE = "broker.type";
    private static final String BROKER_COMMAND = "broker.command";
    private static final String BROKER_CLEAN_BETWEEN_TESTS = "broker.clean.between.tests";
    private static final String BROKER_EXISTING_QPID_WORK = "broker.existing.qpid.work";
    private static final String BROKER_VERSION = "broker.version";
    protected static final String BROKER_READY = "broker.ready";
    private static final String BROKER_STOPPED = "broker.stopped";
    private static final String TEST_OUTPUT = "test.output";
    private static final String BROKER_LOG_INTERLEAVE = "broker.log.interleave";
    private static final String BROKER_LOG_PREFIX = "broker.log.prefix";
    private static final String BROKER_PERSITENT = "broker.persistent";
    public static final String BROKER_PROTOCOL_EXCLUDES = "broker.protocol.excludes";
    public static final String BROKER_PROTOCOL_INCLUDES = "broker.protocol.includes";

    // values
    protected static final String JAVA = "java";
    protected static final String CPP = "cpp";

    protected static final String QPID_HOME = "QPID_HOME";

    public static final int DEFAULT_VM_PORT = 1;
    public static final int DEFAULT_PORT = Integer.getInteger("test.port", ServerConfiguration.DEFAULT_PORT);
    public static final int FAILING_PORT = Integer.parseInt(System.getProperty("test.port.alt"));
    public static final int DEFAULT_MANAGEMENT_PORT = Integer.getInteger("test.mport", ServerConfiguration.DEFAULT_JMXPORT_REGISTRYSERVER);
    public static final int DEFAULT_SSL_PORT = Integer.getInteger("test.port.ssl", ServerConfiguration.DEFAULT_SSL_PORT);

    protected String _brokerLanguage = System.getProperty(BROKER_LANGUAGE, JAVA);
    protected BrokerType _brokerType = BrokerType.valueOf(System.getProperty(BROKER_TYPE, "").toUpperCase());
    protected String _brokerCommand = System.getProperty(BROKER_COMMAND);
    private Boolean _brokerCleanBetweenTests = Boolean.getBoolean(BROKER_CLEAN_BETWEEN_TESTS);
    private final AmqpProtocolVersion _brokerVersion = AmqpProtocolVersion.valueOf(System.getProperty(BROKER_VERSION, ""));
    protected String _output = System.getProperty(TEST_OUTPUT, System.getProperty("java.io.tmpdir"));
    protected Boolean _brokerPersistent = Boolean.getBoolean(BROKER_PERSITENT);

    protected static String _brokerLogPrefix = System.getProperty(BROKER_LOG_PREFIX,"BROKER: ");
    protected static boolean _interleaveBrokerLog = Boolean.getBoolean(BROKER_LOG_INTERLEAVE);

    protected File _outputFile;

    protected PrintStream _testcaseOutputStream;

    protected Map<Integer, BrokerHolder> _brokers = new HashMap<Integer, BrokerHolder>();

    protected InitialContext _initialContext;
    protected AMQConnectionFactory _connectionFactory;

    // the connections created for a given test
    protected List<Connection> _connections = new ArrayList<Connection>();
    public static final String QUEUE = "queue";
    public static final String TOPIC = "topic";

    /** Map to hold test defined environment properties */
    private Map<String, String> _env;

    /** Ensure our messages have some sort of size */
    protected static final int DEFAULT_MESSAGE_SIZE = 1024;

    /** Size to create our message*/
    private int _messageSize = DEFAULT_MESSAGE_SIZE;
    /** Type of message*/
    protected enum MessageType
    {
        BYTES,
        MAP,
        OBJECT,
        STREAM,
        TEXT
    }
    private MessageType _messageType  = MessageType.TEXT;

    public QpidBrokerTestCase(String name)
    {
        super(name);
    }
    
    public QpidBrokerTestCase()
    {
        super();
    }

    public Logger getLogger()
    {
        return QpidBrokerTestCase._logger;
    }

    @Override
    public void runBare() throws Throwable
    {
        String qname = getClass().getName() + "." + getName();

        // Initialize this for each test run
        _env = new HashMap<String, String>();

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        PrintStream out = null;
        PrintStream err = null;

        boolean redirected = _output != null && _output.length() > 0;
        if (redirected)
        {
            _outputFile = new File(String.format("%s/TEST-%s.out", _output, qname));
            out = new PrintStream(new FileOutputStream(_outputFile), true);
            err = new PrintStream(String.format("%s/TEST-%s.err", _output, qname));

            System.setOut(out);
            System.setErr(err);

            if (_interleaveBrokerLog)
            {
                _testcaseOutputStream = out;
            }
            else
            {
                _testcaseOutputStream = new PrintStream(new FileOutputStream(String
                        .format("%s/TEST-%s.broker.out", _output, qname)), true);
            }
        }

        try
        {
            super.runBare();
        }
        catch (Exception e)
        {
            _logger.error("exception", e);
            throw e;
        }
        finally
        {
            stopAllBrokers();

            // reset properties used in the test
            revertSystemProperties();
            revertLoggingLevels();

            if(_brokerCleanBetweenTests)
            {
                final String qpidWork = System.getProperty("QPID_WORK");
                cleanBrokerWork(qpidWork);
                createBrokerWork(qpidWork);
            }

            _logger.info("==========  stop " + getTestName() + " ==========");

            if (redirected)
            {
                System.setErr(oldErr);
                System.setOut(oldOut);
                err.close();
                out.close();
                if (!_interleaveBrokerLog)
                {
                    _testcaseOutputStream.close();
                }
            }
        }
    }

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        if (!_configFile.exists())
        {
            fail("Unable to test without config file:" + _configFile);
        }

        String existingQpidWorkPath = System.getProperty(BROKER_EXISTING_QPID_WORK);
        if(existingQpidWorkPath != null && !existingQpidWorkPath.equals(""))
        {

            String qpidWork = getQpidWork(_brokerType, getPort());
            File existing = new File(existingQpidWorkPath);
            cleanBrokerWork(qpidWork);
            FileUtils.copyRecursive(existing, new File(qpidWork));
        }

        startBroker();
    }

    /**
     * Return the management port in use by the broker on this main port
     *
     * @param mainPort the broker's main port.
     *
     * @return the management port that corresponds to the broker on the given port
     */
    protected int getManagementPort(int mainPort)
    {
        return mainPort + (DEFAULT_MANAGEMENT_PORT - DEFAULT_PORT);
    }

    /**
     * The returned set of port numbers is only a guess because it assumes no ports have been overridden
     * using system properties.
     */
    protected Set<Integer> guessAllPortsUsedByBroker(int mainPort)
    {
        Set<Integer> ports = new HashSet<Integer>();
        int managementPort = getManagementPort(mainPort);
        int connectorServerPort = managementPort + ServerConfiguration.JMXPORT_CONNECTORSERVER_OFFSET;

        ports.add(mainPort);
        ports.add(managementPort);
        ports.add(connectorServerPort);
        ports.add(DEFAULT_SSL_PORT);

        return ports;
    }

    /**
     * Get the Port that is use by the current broker
     *
     * @return the current port
     */
    protected int getPort()
    {
        return getPort(0);
    }

    protected int getPort(int port)
    {
        if (!_brokerType.equals(BrokerType.EXTERNAL))
        {
            return port == 0 ? DEFAULT_PORT : port;
        }
        else
        {
            return port;
        }
    }

    protected String getBrokerCommand(int port) throws MalformedURLException
    {
        final int sslPort = port-1;
        final String protocolExcludesList = getProtocolExcludesList(port, sslPort);
        final String protocolIncludesList = getProtocolIncludesList(port, sslPort);

        return _brokerCommand
                .replace("@PORT", "" + port)
                .replace("@SSL_PORT", "" + sslPort)
                .replace("@MPORT", "" + getManagementPort(port))
                .replace("@CONFIG_FILE", _configFile.toString())
                .replace("@LOG_CONFIG_FILE", _logConfigFile.toString())
                .replace("@EXCLUDES", protocolExcludesList)
                .replace("@INCLUDES", protocolIncludesList);
    }

    public void startBroker() throws Exception
    {
        startBroker(0);
    }

    public void startBroker(int port) throws Exception
    {
        startBroker(port, _testConfiguration, _testVirtualhosts);
    }

    public void startBroker(int port, XMLConfiguration testConfiguration, XMLConfiguration virtualHosts) throws Exception
    {
        port = getPort(port);

        // Save any configuration changes that have been made
        String testConfig = saveTestConfiguration(port, testConfiguration);
        String virtualHostsConfig = saveTestVirtualhosts(port, virtualHosts);

        if(_brokers.get(port) != null)
        {
            throw new IllegalStateException("There is already an existing broker running on port " + port);
        }

        Set<Integer> portsUsedByBroker = guessAllPortsUsedByBroker(port);

        if (_brokerType.equals(BrokerType.INTERNAL) && !existingInternalBroker())
        {
            setConfigurationProperty(ServerConfiguration.MGMT_CUSTOM_REGISTRY_SOCKET, String.valueOf(false));
            testConfig = saveTestConfiguration(port, testConfiguration);
            _logger.info("Set test.config property to: " + testConfig);
            _logger.info("Set test.virtualhosts property to: " + virtualHostsConfig);
            setSystemProperty(TEST_CONFIG, testConfig);
            setSystemProperty(TEST_VIRTUALHOSTS, virtualHostsConfig);

            BrokerOptions options = new BrokerOptions();
            options.setConfigFile(_configFile.getAbsolutePath());
            options.addPort(port);

            addExcludedPorts(port, DEFAULT_SSL_PORT, options);
            addIncludedPorts(port, DEFAULT_SSL_PORT, options);

            options.setJmxPortRegistryServer(getManagementPort(port));

            //Set the log config file, relying on the log4j.configuration system property
            //set on the JVM by the JUnit runner task in module.xml.
            options.setLogConfigFile(_logConfigFile.getAbsolutePath());

            Broker broker = new Broker();
            _logger.info("starting internal broker (same JVM)");
            broker.startup(options);

            _brokers.put(port, new InternalBrokerHolder(broker, System.getProperty("QPID_WORK"), portsUsedByBroker));
        }
        else if (!_brokerType.equals(BrokerType.EXTERNAL))
        {
            // Add the port to QPID_WORK to ensure unique working dirs for multi broker tests
            final String qpidWork = getQpidWork(_brokerType, port);
            String cmd = getBrokerCommand(port);
            _logger.info("starting external broker: " + cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split("\\s+"));
            pb.redirectErrorStream(true);
            Map<String, String> processEnv = pb.environment();
            String qpidHome = System.getProperty(QPID_HOME);
            processEnv.put(QPID_HOME, qpidHome);
            //Augment Path with bin directory in QPID_HOME.
            processEnv.put("PATH", processEnv.get("PATH").concat(File.pathSeparator + qpidHome + "/bin"));

            //Add the test name to the broker run.
            // DON'T change PNAME, qpid.stop needs this value.
            processEnv.put("QPID_PNAME", "-DPNAME=QPBRKR -DTNAME=\"" + getTestName() + "\"");
            processEnv.put("QPID_WORK", qpidWork);

            // Use the environment variable to set amqj.logging.level for the broker
            // The value used is a 'server' value in the test configuration to
            // allow a differentiation between the client and broker logging levels.
            if (System.getProperty("amqj.server.logging.level") != null)
            {
                setBrokerEnvironment("AMQJ_LOGGING_LEVEL", System.getProperty("amqj.server.logging.level"));
            }

            // Add all the environment settings the test requested
            if (!_env.isEmpty())
            {
                for (Map.Entry<String, String> entry : _env.entrySet())
                {
                    processEnv.put(entry.getKey(), entry.getValue());
                }
            }


            // Add default test logging levels that are used by the log4j-test
            // Use the convenience methods to push the current logging setting
            // in to the external broker's QPID_OPTS string.
            if (System.getProperty("amqj.protocol.logging.level") != null)
            {
                setSystemProperty("amqj.protocol.logging.level");
            }
            if (System.getProperty("root.logging.level") != null)
            {
                setSystemProperty("root.logging.level");
            }

            // set test.config and test.virtualhosts
            String qpidOpts = " -D" + TEST_CONFIG + "=" + testConfig + " -D" + TEST_VIRTUALHOSTS + "=" + virtualHostsConfig;

            // Add all the specified system properties to QPID_OPTS
            if (!_propertiesSetForBroker.isEmpty())
            {
                for (String key : _propertiesSetForBroker.keySet())
                {
                    qpidOpts += " -D" + key + "=" + _propertiesSetForBroker.get(key);
                }
            }
            if (processEnv.containsKey("QPID_OPTS"))
            {
                qpidOpts = processEnv.get("QPID_OPTS") + qpidOpts;
            }
            processEnv.put("QPID_OPTS", qpidOpts);

            _logger.info("Set test.config property to: " + testConfig);
            _logger.info("Set test.virtualhosts property to: " + virtualHostsConfig);

            // cpp broker requires that the work directory is created
            createBrokerWork(qpidWork);

            Process process = pb.start();

            Piper p = new Piper(process.getInputStream(),
                                _testcaseOutputStream,
                                System.getProperty(BROKER_READY),
                                System.getProperty(BROKER_STOPPED),
                                _interleaveBrokerLog ? _brokerLogPrefix : null);

            p.start();

            if (!p.await(30, TimeUnit.SECONDS))
            {
                _logger.info("broker failed to become ready (" + p.getReady() + "):" + p.getStopLine());
                //Ensure broker has stopped
                process.destroy();
                cleanBrokerWork(qpidWork);
                throw new RuntimeException("broker failed to become ready:"
                                           + p.getStopLine());
            }

            try
            {
                //test that the broker is still running and hasn't exited unexpectedly
                int exit = process.exitValue();
                _logger.info("broker aborted: " + exit);
                cleanBrokerWork(qpidWork);
                throw new RuntimeException("broker aborted: " + exit);
            }
            catch (IllegalThreadStateException e)
            {
                // this is expect if the broker started successfully
            }

            _brokers.put(port, new SpawnedBrokerHolder(process, qpidWork, portsUsedByBroker));
        }
    }

    private void addExcludedPorts(int port, int sslPort, BrokerOptions options)
    {
        final String protocolExcludesList = getProtocolExcludesList(port, sslPort);

        if (protocolExcludesList.equals(""))
        {
            return;
        }
        final String[] toks = protocolExcludesList.split("\\s");

        if(toks.length % 2 != 0)
        {
            throw new IllegalArgumentException("Must be an even number of tokens in '" + protocolExcludesList + "'");
        }
        for (int i = 0; i < toks.length; i=i+2)
        {
            String excludeArg = toks[i];
            final int excludedPort = Integer.parseInt(toks[i+1]);
            options.addExcludedPort(ProtocolExclusion.lookup(excludeArg), excludedPort);

            _logger.info("Adding protocol exclusion " + excludeArg + " " + excludedPort);
        }
    }

    protected String getProtocolExcludesList(int port, int sslPort)
    {
        return System.getProperty(BROKER_PROTOCOL_EXCLUDES,"").replace("@PORT", "" + port).replace("@SSL_PORT", "" + sslPort);
    }

    private String getProtocolIncludesList(int port, int sslPort)
    {
        return System.getProperty(BROKER_PROTOCOL_INCLUDES, "").replace("@PORT", "" + port).replace("@SSL_PORT", "" + sslPort);
    }

    private void addIncludedPorts(int port, int sslPort, BrokerOptions options)
    {
        final String protocolIncludesList = getProtocolIncludesList(port, sslPort);

        if (protocolIncludesList.equals(""))
        {
            return;
        }
        final String[] toks = protocolIncludesList.split("\\s");

        if(toks.length % 2 != 0)
        {
            throw new IllegalArgumentException("Must be an even number of tokens in '" + protocolIncludesList + "'");
        }
        for (int i = 0; i < toks.length; i=i+2)
        {
            String includeArg = toks[i];
            final int includedPort = Integer.parseInt(toks[i+1]);
            options.addIncludedPort(ProtocolInclusion.lookup(includeArg), includedPort);

            _logger.info("Adding protocol inclusion " + includeArg + " " + includedPort);
        }
    }

    private boolean existingInternalBroker()
    {
        for(BrokerHolder holder : _brokers.values())
        {
            if(holder instanceof InternalBrokerHolder)
            {
                return true;
            }
        }

        return false;
    }

    private String getQpidWork(BrokerType broker, int port)
    {
        if (!broker.equals(BrokerType.EXTERNAL))
        {
            return System.getProperty("QPID_WORK")+ "/" + port;
        }

        return System.getProperty("QPID_WORK");
    }

    public String getTestConfigFile()
    {
        return getTestConfigFile(getPort());
    }

    public String getTestConfigFile(int port)
    {
        return _output + "/" + getTestQueueName() + "-" + port + "-config.xml";
    }

    public String getTestVirtualhostsFile(int port)
    {
        return _output + "/" + getTestQueueName() + "-" + port + "-virtualhosts.xml";
    }

    private String relativeToQpidHome(String file)
    {
        return file.replace(System.getProperty(QPID_HOME,"QPID_HOME") + "/","");
    }

    protected void saveTestConfiguration() throws ConfigurationException
    {
        String relative = saveTestConfiguration(getPort(), _testConfiguration);
        _logger.info("Set test.config property to: " + relative);
        setSystemProperty(TEST_CONFIG, relative);
    }

    protected String saveTestConfiguration(int port, XMLConfiguration testConfiguration) throws ConfigurationException
    {
        // Specify the test config file
        String testConfig = getTestConfigFile(port);
        String relative = relativeToQpidHome(testConfig);

        _logger.info("Saving test virtualhosts file at: " + testConfig);

        // Create the file if configuration does not exist
        if (testConfiguration.isEmpty())
        {
            testConfiguration.addProperty("__ignore", "true");
        }
        testConfiguration.save(testConfig);
        return relative;
    }

    protected void saveTestVirtualhosts() throws ConfigurationException
    {
        String relative = saveTestVirtualhosts(getPort(), _testVirtualhosts);
        _logger.info("Set test.virtualhosts property to: " + relative);
        setSystemProperty(TEST_VIRTUALHOSTS, relative);
    }

    protected String saveTestVirtualhosts(int port, XMLConfiguration virtualHostConfiguration) throws ConfigurationException
    {
        // Specify the test virtualhosts file
        String testVirtualhosts = getTestVirtualhostsFile(port);
        String relative = relativeToQpidHome(testVirtualhosts);

        _logger.info("Set test.virtualhosts property to: " + testVirtualhosts);

        // Create the file if configuration does not exist
        if (virtualHostConfiguration.isEmpty())
        {
            virtualHostConfiguration.addProperty("__ignore", "true");
        }
        virtualHostConfiguration.save(testVirtualhosts);
        return relative;
    }

    protected void cleanBrokerWork(final String qpidWork)
    {
        if (qpidWork != null)
        {
            _logger.info("Cleaning broker work dir: " + qpidWork);

            File file = new File(qpidWork);
            if (file.exists())
            {
                final boolean success = FileUtils.delete(file, true);
                if(!success)
                {
                    throw new RuntimeException("Failed to recursively delete beneath : " + file);
                }
            }
        }
    }

    protected void createBrokerWork(final String qpidWork)
    {
        if (qpidWork != null)
        {
            final File dir = new File(qpidWork);
            dir.mkdirs();
            if (!dir.isDirectory())
            {
                throw new RuntimeException("Failed to created Qpid work directory : " + qpidWork);
            }
        }
    }

    public void stopBroker()
    {
        stopBroker(0);
    }

    public void stopAllBrokers()
    {
        boolean exceptionOccured = false;
        Set<Integer> runningBrokerPorts = new HashSet<Integer>(getBrokerPortNumbers());
        for (int brokerPortNumber : runningBrokerPorts)
        {
            if (!stopBrokerSafely(brokerPortNumber))
            {
                exceptionOccured = true;
            }
        }
        if (exceptionOccured)
        {
            throw new RuntimeException("Exception occured on stopping of test broker. Please, examine logs for details");
        }
    }

    protected boolean stopBrokerSafely(int brokerPortNumber)
    {
        boolean success = true;
        BrokerHolder broker = _brokers.get(brokerPortNumber);
        try
        {
            stopBroker(brokerPortNumber);
        }
        catch(Exception e)
        {
            success = false;
            _logger.error("Failed to stop broker " + broker + " at port " + brokerPortNumber, e);
            if (broker != null)
            {
                // save the thread dump in case of dead locks
                try
                {
                    _logger.error("Broker " + broker + " thread dump:" + broker.dumpThreads());
                }
                finally
                {
                    // try to kill broker
                    try
                    {
                        broker.kill();
                    }
                    catch(Exception killException)
                    {
                        // ignore
                    }
                }
            }
        }
        return success;
    }

    public void stopBroker(int port)
    {
        if (isBrokerPresent(port))
        {
            port = getPort(port);

            _logger.info("stopping broker on port : " + port);
            BrokerHolder broker = _brokers.remove(port);
            broker.shutdown();
        }
    }

    public void killBroker()
    {
        killBroker(0);
    }

    public void killBroker(int port)
    {
        if (isBrokerPresent(port))
        {
            port = getPort(port);

            _logger.info("killing broker on port : " + port);
            BrokerHolder broker = _brokers.remove(port);
            broker.kill();
        }
    }

    public boolean isBrokerPresent(int port)
    {
        port = getPort(port);

        return _brokers.containsKey(port);
    }

    public BrokerHolder getBroker(int port) throws Exception
    {
        port = getPort(port);
        return _brokers.get(port);
    }

    public Set<Integer> getBrokerPortNumbers()
    {
        return new HashSet<Integer>(_brokers.keySet());
    }

    /**
     * Attempt to set the Java Broker to use the BDBMessageStore for persistence
     * Falling back to the DerbyMessageStore if
     *
     * @param virtualhost - The virtualhost to modify
     *
     * @throws ConfigurationException - when reading/writing existing configuration
     * @throws IOException            - When creating a temporary file.
     */
    protected void makeVirtualHostPersistent(String virtualhost)
            throws ConfigurationException, IOException
    {
        Class<?> storeClass = null;
        try
        {
            // Try and lookup the BDB class
            storeClass = Class.forName("org.apache.qpid.server.store.berkeleydb.BDBMessageStore");
        }
        catch (ClassNotFoundException e)
        {
            // No BDB store, we'll use Derby instead.
            storeClass = DerbyMessageStore.class;
        }


        setConfigurationProperty("virtualhosts.virtualhost." + virtualhost + ".store.class",
                                    storeClass.getName());
        setConfigurationProperty("virtualhosts.virtualhost." + virtualhost + ".store." + MessageStoreConstants.ENVIRONMENT_PATH_PROPERTY,
                                   "${QPID_WORK}/" + virtualhost);
    }

    /**
     * Get a property value from the current configuration file.
     *
     * @param property the property to lookup
     *
     * @return the requested String Value
     *
     * @throws org.apache.commons.configuration.ConfigurationException
     *
     */
    protected String getConfigurationStringProperty(String property) throws ConfigurationException
    {
        // Call save Configuration to be sure we have saved the test specific
        // file. As the optional status
        saveTestConfiguration();
        saveTestVirtualhosts();

        ServerConfiguration configuration = new ServerConfiguration(_configFile);
        // Don't need to configuration.configure() here as we are just pulling
        // values directly by String.
        return configuration.getConfig().getString(property);
    }

    /**
     * Set a configuration Property for this test run.
     *
     * This creates a new configuration based on the current configuration
     * with the specified property change.
     *
     * Multiple calls to this method will result in multiple temporary
     * configuration files being created.
     *
     * @param property the configuration property to set
     * @param value    the new value
     *
     * @throws ConfigurationException when loading the current config file
     * @throws IOException            when writing the new config file
     */
    public void setConfigurationProperty(String property, String value)
            throws ConfigurationException, IOException
    {
        // Choose which file to write the property to based on prefix.
        if (property.startsWith("virtualhosts"))
        {
            _testVirtualhosts.setProperty(StringUtils.substringAfter(property, "virtualhosts."), value);
        }
        else
        {
            _testConfiguration.setProperty(property, value);
        }
    }

    /**
     * Set a System property that is to be applied only to the external test
     * broker.
     *
     * This is a convenience method to enable the setting of a -Dproperty=value
     * entry in QPID_OPTS
     *
     * This is only useful for the External Java Broker tests.
     *
     * @param property the property name
     * @param value the value to set the property to
     */
    protected void setBrokerOnlySystemProperty(String property, String value)
    {
        if (!_propertiesSetForBroker.containsKey(property))
        {
            _propertiesSetForBroker.put(property, value);
        }

    }

    /**
     * Set a System (-D) property for this test run.
     *
     * This convenience method copies the current VMs System Property
     * for the external VM Broker.
     *
     * @param property the System property to set
     */
    protected void setSystemProperty(String property)
    {
        setSystemProperty(property, System.getProperty(property));
    }

    /**
     * Set a System property for the duration of this test.
     *
     * When the test run is complete the value will be reverted.
     *
     * The values set using this method will also be propogated to the external
     * Java Broker via a -D value defined in QPID_OPTS.
     *
     * If the value should not be set on the broker then use
     * setTestClientSystemProperty().
     *
     * @param property the property to set
     * @param value    the new value to use
     */
    protected void setSystemProperty(String property, String value)
    {
        // Record the value for the external broker
        _propertiesSetForBroker.put(property, value);

        //Set the value for the test client vm aswell.
        setTestClientSystemProperty(property, value);
    }

    /**
     * Set a System  property for the client (and broker if using the same vm) of this test.
     *
     * @param property The property to set
     * @param value the value to set it to.
     */
    protected void setTestClientSystemProperty(String property, String value)
    {
        setTestSystemProperty(property, value);
    }

    /**
     * Restore the System property values that were set before this test run.
     */
    protected void revertSystemProperties()
    {
        revertTestSystemProperties();

        // We don't change the current VMs settings for Broker only properties
        // so we can just clear this map
        _propertiesSetForBroker.clear();
    }

    /**
     * Add an environment variable for the external broker environment
     *
     * @param property the property to set
     * @param value    the value to set it to
     */
    protected void setBrokerEnvironment(String property, String value)
    {
        _env.put(property, value);
    }

    /**
     * Check whether the broker is an 0.8
     *
     * @return true if the broker is an 0_8 version, false otherwise.
     */
    public boolean isBroker08()
    {
        return _brokerVersion.equals(AmqpProtocolVersion.v0_8);
    }

    public boolean isBroker010()
    {
        return _brokerVersion.equals(AmqpProtocolVersion.v0_10);
    }

    protected boolean isJavaBroker()
    {
        return _brokerLanguage.equals("java");
    }

    protected boolean isCppBroker()
    {
        return _brokerLanguage.equals("cpp");
    }

    protected boolean isExternalBroker()
    {
        return !isInternalBroker();
    }

    protected boolean isInternalBroker()
    {
        return _brokerType.equals(BrokerType.INTERNAL);
    }

    protected boolean isBrokerStorePersistent()
    {
        return _brokerPersistent;
    }

    public void restartBroker() throws Exception
    {
        restartBroker(0);
    }

    public void restartBroker(int port) throws Exception
    {
        stopBroker(port);
        startBroker(port);
    }

    /**
     * we assume that the environment is correctly set
     * i.e. -Djava.naming.provider.url="..//example010.properties"
     *
     * @return an initial context
     *
     * @throws NamingException if there is an error getting the context
     */
    public InitialContext getInitialContext() throws NamingException
    {
        _logger.info("get InitialContext");
        if (_initialContext == null)
        {
            _initialContext = new InitialContext();
        }
        return _initialContext;
    }

    /**
     * Get the default connection factory for the currently used broker
     * Default factory is "local"
     *
     * @return A connection factory
     *
     * @throws Exception if there is an error getting the factory
     */
    public AMQConnectionFactory getConnectionFactory() throws NamingException
    {
        _logger.info("get ConnectionFactory");
        if (_connectionFactory == null)
        {
            if (Boolean.getBoolean("profile.use_ssl"))
            {
                _connectionFactory = getConnectionFactory("default.ssl");
            }
            else
            {
                _connectionFactory = getConnectionFactory("default");
            }
        }
        return _connectionFactory;
    }

    /**
     * Get a connection factory for the currently used broker
     *
     * @param factoryName The factory name
     *
     * @return A connection factory
     *
     * @throws Exception if there is an error getting the tactory
     */
    public AMQConnectionFactory getConnectionFactory(String factoryName) throws NamingException
    {
        return (AMQConnectionFactory) getInitialContext().lookup(factoryName);
    }

    public Connection getConnection() throws JMSException, NamingException
    {
        return getConnection(GUEST_USERNAME, GUEST_PASSWORD);
    }

    public Connection getConnection(ConnectionURL url) throws JMSException
    {
        _logger.info(url.getURL());
        Connection connection = new AMQConnectionFactory(url).createConnection(url.getUsername(), url.getPassword());

        _connections.add(connection);

        return connection;
    }

    /**
     * Get a connection (remote or in-VM)
     *
     * @param username The user name
     * @param password The user password
     *
     * @return a newly created connection
     *
     * @throws Exception if there is an error getting the connection
     */
    public Connection getConnection(String username, String password) throws JMSException, NamingException
    {
        _logger.info("get connection");
        Connection con = getConnectionFactory().createConnection(username, password);
        //add the connection in the list of connections
        _connections.add(con);
        return con;
    }

    public Connection getClientConnection(String username, String password, String id) throws JMSException, URLSyntaxException, AMQException, NamingException
    {
        _logger.info("get Connection");
        Connection con = getConnectionFactory().createConnection(username, password, id);
        //add the connection in the list of connections
        _connections.add(con);
        return con;
    }

    /**
     * Return a uniqueName for this test.
     * In this case it returns a queue Named by the TestCase and TestName
     *
     * @return String name for a queue
     */
    protected String getTestQueueName()
    {
        return getClass().getSimpleName() + "-" + getName();
    }

    /**
     * Return a Queue specific for this test.
     * Uses getTestQueueName() as the name of the queue
     * @return
     */
    public Queue getTestQueue()
    {
        return new AMQQueue(ExchangeDefaults.DIRECT_EXCHANGE_NAME, getTestQueueName());
    }

    /**
     * Return a Topic specific for this test.
     * Uses getTestQueueName() as the name of the topic
     * @return
     */
    public Topic getTestTopic()
    {
        return new AMQTopic(ExchangeDefaults.TOPIC_EXCHANGE_NAME, getTestQueueName());
    }

    protected void tearDown() throws java.lang.Exception
    {
        super.tearDown();

        // close all the connections used by this test.
        for (Connection c : _connections)
        {
            c.close();
        }
    }

    /**
     * Consume all the messages in the specified queue. Helper to ensure
     * persistent tests don't leave data behind.
     *
     * @param queue the queue to purge
     *
     * @return the count of messages drained
     *
     * @throws Exception if a problem occurs
     */
    protected int drainQueue(Queue queue) throws Exception
    {
        Connection connection = getConnection();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        MessageConsumer consumer = session.createConsumer(queue);

        connection.start();

        int count = 0;
        while (consumer.receive(1000) != null)
        {
            count++;
        }

        connection.close();

        return count;
    }

    /**
     * Send messages to the given destination.
     *
     * If session is transacted then messages will be committed before returning
     *
     * @param session the session to use for sending
     * @param destination where to send them to
     * @param count no. of messages to send
     *
     * @return the sent messages
     *
     * @throws Exception
     */
    public List<Message> sendMessage(Session session, Destination destination,
                                     int count) throws Exception
    {
        return sendMessage(session, destination, count, 0, 0);
    }

    /**
     * Send messages to the given destination.
     *
     * If session is transacted then messages will be committed before returning
     *
     * @param session the session to use for sending
     * @param destination where to send them to
     * @param count no. of messages to send
     *
     * @param batchSize the batchSize in which to commit, 0 means no batching,
     * but a single commit at the end
     * @return the sent message
     *
     * @throws Exception
     */
    public List<Message> sendMessage(Session session, Destination destination,
                                     int count, int batchSize) throws Exception
    {
        return sendMessage(session, destination, count, 0, batchSize);
    }

    /**
     * Send messages to the given destination.
     *
     * If session is transacted then messages will be committed before returning
     *
     * @param session the session to use for sending
     * @param destination where to send them to
     * @param count no. of messages to send
     *
     * @param offset offset allows the INDEX value of the message to be adjusted.
     * @param batchSize the batchSize in which to commit, 0 means no batching,
     * but a single commit at the end
     * @return the sent message
     *
     * @throws Exception
     */
    public List<Message> sendMessage(Session session, Destination destination,
                                     int count, int offset, int batchSize) throws Exception
    {
        List<Message> messages = new ArrayList<Message>(count);

        MessageProducer producer = session.createProducer(destination);

        int i = offset;
        for (; i < (count + offset); i++)
        {
            Message next = createNextMessage(session, i);

            producer.send(next);

            if (session.getTransacted() && batchSize > 0)
            {
                if (i % batchSize == 0)
                {
                    session.commit();
                }

            }

            messages.add(next);
        }

        // Ensure we commit the last messages
        // Commit the session if we are transacted and
        // we have no batchSize or
        // our count is not divible by batchSize.
        if (session.getTransacted() &&
            ( batchSize == 0 || (i-1) % batchSize != 0))
        {
            session.commit();
        }

        return messages;
    }

    public Message createNextMessage(Session session, int msgCount) throws JMSException
    {
        Message message = createMessage(session, _messageSize);
        message.setIntProperty(INDEX, msgCount);

        return message;

    }

    public Message createMessage(Session session, int messageSize) throws JMSException
    {
        String payload = new String(new byte[messageSize]);

        Message message;

        switch (_messageType)
        {
            case BYTES:
                message = session.createBytesMessage();
                ((BytesMessage) message).writeUTF(payload);
                break;
            case MAP:
                message = session.createMapMessage();
                ((MapMessage) message).setString(CONTENT, payload);
                break;
            default: // To keep the compiler happy
            case TEXT:
                message = session.createTextMessage();
                ((TextMessage) message).setText(payload);
                break;
            case OBJECT:
                message = session.createObjectMessage();
                ((ObjectMessage) message).setObject(payload);
                break;
            case STREAM:
                message = session.createStreamMessage();
                ((StreamMessage) message).writeString(payload);
                break;
        }

        return message;
    }

    protected int getMessageSize()
    {
        return _messageSize;
    }

    protected void setMessageSize(int byteSize)
    {
        _messageSize = byteSize;
    }

    public ConnectionURL getConnectionURL() throws NamingException
    {
        return getConnectionFactory().getConnectionURL();
    }

    public BrokerDetails getBroker()
    {
        try
        {
            if (getConnectionFactory().getConnectionURL().getBrokerCount() > 0)
            {
                return getConnectionFactory().getConnectionURL().getBrokerDetails(0);
            }
            else
            {
                fail("No broker details are available.");
            }
        }
        catch (NamingException e)
        {
            fail(e.getMessage());
        }

        //keep compiler happy
        return null;
    }

    /**
     * Reloads the broker security configuration using the ApplicationRegistry (InVM brokers) or the
     * ConfigurationManagementMBean via the JMX interface (Standalone brokers, management must be
     * enabled before calling the method).
     */
    public void reloadBrokerSecurityConfig() throws Exception
    {
        JMXTestUtils jmxu = new JMXTestUtils(this);
        jmxu.open();

        try
        {
            ConfigurationManagement configMBean = jmxu.getConfigurationManagement();
            configMBean.reloadSecurityConfiguration();
        }
        finally
        {
            jmxu.close();
        }

        LogMonitor _monitor = new LogMonitor(_outputFile);
        assertTrue("The expected server security configuration reload did not occur",
                _monitor.waitForMessage(ServerConfiguration.SECURITY_CONFIG_RELOADED, LOGMONITOR_TIMEOUT));
    }

    protected int getFailingPort()
    {
        return FAILING_PORT;
    }

    public XMLConfiguration getTestVirtualhosts()
    {
        return _testVirtualhosts;
    }

    public void setTestVirtualhosts(XMLConfiguration testVirtualhosts)
    {
        _testVirtualhosts = testVirtualhosts;
    }

    public XMLConfiguration getTestConfiguration()
    {
        return _testConfiguration;
    }

    public void setTestConfiguration(XMLConfiguration testConfiguration)
    {
        _testConfiguration = testConfiguration;
    }
}
