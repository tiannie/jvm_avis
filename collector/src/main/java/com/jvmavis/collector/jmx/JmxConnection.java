package com.jvmavis.collector.jmx;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class JmxConnection implements AutoCloseable {
    private final JMXConnector connector;
    private final MBeanServerConnection connection;

    private JmxConnection(JMXConnector connector, MBeanServerConnection connection) {
        this.connector = connector;
        this.connection = connection;
    }

    public static JmxConnection connect(String host, int port) throws IOException {
        String url = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
        JMXServiceURL serviceURL = new JMXServiceURL(url);
        Map<String, Object> env = new HashMap<>();
        env.put("jmx.remote.x.request.timeout", 5000);
        env.put("jmx.remote.x.notification.fetch.timeout", 5000);
        JMXConnector connector = JMXConnectorFactory.connect(serviceURL, env);
        return new JmxConnection(connector, connector.getMBeanServerConnection());
    }

    public MBeanServerConnection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connector.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
