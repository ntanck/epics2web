package org.jlab.epics2web.websocket;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import org.jlab.epics2web.Application;

/**
 * Controller for the EPICS web socket monitor.
 * 
 * @author ryans
 */
@ServerEndpoint(value = "/monitor", configurator = AuditServerEndpointConfigurator.class)
public class MonitorEndpoint {

    private static final Logger LOGGER = Logger.getLogger(MonitorEndpoint.class.getName());

    @OnOpen
    public void open(Session session, EndpointConfig config) {
        //LOGGER.log(Level.FINEST, "open");
        if (session != null) {
            Application.sessionManager.addClient(session);

            WebSocketAuditContext context = WebSocketAuditContext.getCurrentInstance();

            if (context != null) {
                Map<String, List<String>> headers = context.getHeaders();

                String ip = null;
                List<String> xForwardedForList = headers.get("X-Forwarded-For");
                String xForwardedFor = null;
                if (xForwardedForList != null && !xForwardedForList.isEmpty()) {
                    xForwardedFor = xForwardedForList.get(0);
                }

                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    String[] ipArray = xForwardedFor.split(",");
                    ip = ipArray[0].trim(); // first one, if more than one
                }

                List<String> agentList = headers.get("user-agent");
                String agent = null;
                if (agentList != null && !agentList.isEmpty()) {
                    agent = agentList.get(0);
                }

                if (ip == null) {
                    ip = context.getRemoteAddr();
                    if (ip == null) {
                        ip = "unknown";
                    }
                }

                session.getUserProperties().put("agent", agent);
                session.getUserProperties().put("ip", ip);

                // Try to prevent classloader leak
                WebSocketAuditContext.setCurrentInstance(null);
            }
        }
    }

    @OnClose
    public void close(Session session, CloseReason reason) {
        //LOGGER.log(Level.FINEST, "close; Reason: {0}", reason);
        if (session != null) {
            Application.sessionManager.removeClient(session);
        }
    }

    @OnError
    public void error(Session session, Throwable t) {
        LOGGER.log(Level.WARNING, "error", t);
        if (session != null) {
            Application.sessionManager.removeClient(session);
        }

        // Try to prevent classloader leak
        WebSocketAuditContext.setCurrentInstance(null);
    }

    @OnMessage
    public String onMessage(String message, Session session) {
        //LOGGER.log(Level.FINEST, "Client message: {0}", message);
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject obj = reader.readObject();

            String type = obj.getString("type");

            if ("ping".equals(type)) {
                /*LOGGER.log(Level.FINEST, "ping recieved");*/
                Application.sessionManager.sendPong(session);
            } else if ("monitor".equals(type)) {
                JsonArray pvs = obj.getJsonArray("pvs");
                Set<String> pvSet = Application.sessionManager.getPvSetFromJson(pvs);

                Application.sessionManager.addPvs(session, pvSet);
            } else if ("clear".equals(type)) {
                JsonArray pvs = obj.getJsonArray("pvs");
                Set<String> pvSet = Application.sessionManager.getPvSetFromJson(pvs);

                Application.sessionManager.clearPvs(session, pvSet);
            } else {
                LOGGER.log(Level.WARNING, "Unknown client request: {0}", message);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to read client message: " + message, e);
        }
        return null;
    }
}