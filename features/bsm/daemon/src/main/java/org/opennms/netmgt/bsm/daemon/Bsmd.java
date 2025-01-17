/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2009-2015 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2015 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.bsm.daemon;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.bsm.service.BusinessServiceManager;
import org.opennms.netmgt.bsm.service.BusinessServiceStateChangeHandler;
import org.opennms.netmgt.bsm.service.BusinessServiceStateMachine;
import org.opennms.netmgt.bsm.service.internal.AlarmWrapperImpl;
import org.opennms.netmgt.bsm.service.internal.SeverityMapper;
import org.opennms.netmgt.bsm.service.model.AlarmWrapper;
import org.opennms.netmgt.bsm.service.model.BusinessService;
import org.opennms.netmgt.bsm.service.model.Status;
import org.opennms.netmgt.bsm.service.model.graph.BusinessServiceGraph;
import org.opennms.netmgt.bsm.service.model.graph.GraphVertex;
import org.opennms.netmgt.bsm.service.model.graph.internal.GraphAlgorithms;
import org.opennms.netmgt.config.api.EventConfDao;
import org.opennms.netmgt.daemon.DaemonTools;
import org.opennms.netmgt.daemon.SpringServiceDaemon;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventProxyException;
import org.opennms.netmgt.events.api.annotations.EventHandler;
import org.opennms.netmgt.events.api.annotations.EventListener;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.eventconf.AlarmData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * This daemon is responsible for driving the Business Service state machine by:
 *  1) Updating the state machine with Alarms when they are created, deleted or updated
 *  2) Sending events on the event bus when the operational status of a Business Service changes
 *  3) Reloading the Business Service configuration in the state machine when requested
 *
 * @author jwhite
 */
@EventListener(name=Bsmd.NAME, logPrefix="bsmd")
public class Bsmd implements SpringServiceDaemon, BusinessServiceStateChangeHandler, AlarmLifecycleListener {
    private static final Logger LOG = LoggerFactory.getLogger(Bsmd.class);

    protected static final long DEFAULT_POLL_INTERVAL = 30; // seconds

    protected static final String POLL_INTERVAL_KEY = "org.opennms.features.bsm.pollInterval";

    public static final String NAME = "Bsmd";

    public static final long RELOAD_DELAY = 1000;

    @Autowired
    @Qualifier("eventIpcManager")
    private EventIpcManager m_eventIpcManager;

    @Autowired
    private EventConfDao m_eventConfDao;

    @Autowired
    private TransactionTemplate m_template;

    @Autowired
    private BusinessServiceStateMachine m_stateMachine;

    @Autowired
    private BusinessServiceManager m_manager;

    private boolean m_verifyReductionKeys = true;

    private ScheduledExecutorService daemonReloadScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("bsm-daemon-reload-%d").build());

    {
        {
            daemonReloadScheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    if (reloadConfigurationAt > 0L && reloadConfigurationAt < System.currentTimeMillis()) {
                        reloadConfigurationAt = 0L;
                        final EventBuilder eventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_UEI, "bsmd");
                        eventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "bsmd");
                        m_eventIpcManager.sendNow(eventBuilder.getEvent());
                    }
                }
            }, RELOAD_DELAY, RELOAD_DELAY, TimeUnit.MILLISECONDS);
        }
    }

    private volatile long reloadConfigurationAt = 0L;

    @Override
    public void afterPropertiesSet() throws Exception {
        Objects.requireNonNull(m_stateMachine, "stateMachine cannot be null");

        LOG.info("Initializing bsmd...");
        m_stateMachine.addHandler(this, null);
    }

    @Override
    public void start() throws Exception {
        Objects.requireNonNull(m_manager, "businessServiceDao cannot be null");
        Objects.requireNonNull(m_eventIpcManager, "eventIpcManager cannot be null");
        Objects.requireNonNull(m_eventConfDao, "eventConfDao cannot be null");

        handleConfigurationChanged();
    }

    @Override
    public void handleAlarmSnapshot(List<OnmsAlarm> alarms) {
        final List<AlarmWrapper> wrappedAlarms = alarms.stream()
                .map(AlarmWrapperImpl::new)
                .collect(Collectors.toList());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Handling {} alarms.", alarms.size());
            LOG.trace("Handling alarms: {}", alarms);
        }
        m_stateMachine.handleAllAlarms(wrappedAlarms);
    }

    @Override
    public void preHandleAlarmSnapshot() {
        // pass, nothing to do here
    }

    @Override
    public void postHandleAlarmSnapshot() {
        // pass, nothing to do here
    }

    @Override
    public void handleNewOrUpdatedAlarm(OnmsAlarm alarm) {
        final AlarmWrapperImpl alarmWrapper = new AlarmWrapperImpl(alarm);
        LOG.debug("Handling alarm with id: {}, reduction key: {} and severity: {} and status: {}", alarm.getId(), alarm.getReductionKey(), alarm.getSeverity(), alarmWrapper.getStatus());
        m_stateMachine.handleNewOrUpdatedAlarm(alarmWrapper);
    }

    @Override
    public void handleDeletedAlarm(int alarmId, String reductionKey) {
        LOG.debug("Handling delete for alarm with id: {} and reduction key: {}", alarmId, reductionKey);
        m_stateMachine.handleNewOrUpdatedAlarm(new AlarmWrapper() {
            @Override
            public String getReductionKey() {
                return reductionKey;
            }

            @Override
            public Status getStatus() {
                return Status.INDETERMINATE;
            }
        });
    }

    private Collection<BusinessService> searchPredecessors(final GraphVertex leaf) {
        final Set<BusinessService> businessServices = new HashSet<>();
        final Collection<GraphVertex> predecessors = m_stateMachine.getGraph().getPredecessors(leaf);
        for(final GraphVertex graphVertex : predecessors) {
            if (graphVertex.getBusinessService() != null) {
                businessServices.add(graphVertex.getBusinessService());
            } else {
                businessServices.addAll(searchPredecessors(graphVertex));
            }
        }
        return businessServices;
    }

    @EventHandler(ueis = {EventConstants.SERVICE_DELETED_EVENT_UEI, EventConstants.INTERFACE_DELETED_EVENT_UEI, EventConstants.NODE_DELETED_EVENT_UEI, EventConstants.APPLICATION_DELETED_EVENT_UEI})
    public void serviceInterfaceOrNodeDeleted(Event e) {
        final Set<String> reductionKeys = m_stateMachine.getGraph().getReductionKeys();

        if (EventConstants.NODE_DELETED_EVENT_UEI.equals(e.getUei()) && reductionKeys.contains(String.format("uei.opennms.org/nodes/nodeDown::%d", e.getNodeid()))
                || EventConstants.INTERFACE_DELETED_EVENT_UEI.equals(e.getUei()) && reductionKeys.contains(String.format("uei.opennms.org/nodes/interfaceDown::%d:%s", e.getNodeid(), e.getInterface()))
                || EventConstants.SERVICE_DELETED_EVENT_UEI.equals(e.getUei()) && reductionKeys.contains(String.format("uei.opennms.org/nodes/nodeLostService::%d:%s:%s", e.getNodeid(), e.getInterface(), e.getService()))
                || EventConstants.APPLICATION_DELETED_EVENT_UEI.equals(e.getUei()) && m_stateMachine.getGraph().getVertexByApplicationId(Integer.parseInt(e.getParm("applicationId").getValue().getContent())) != null) {

            final Set<BusinessService> affectedBusinessServices = new HashSet<>();
            String cause = "an associated entity";

            if (EventConstants.APPLICATION_DELETED_EVENT_UEI.equals(e.getUei())) {
                final GraphVertex vertex = m_stateMachine.getGraph().getVertexByApplicationId(Integer.parseInt(e.getParm("applicationId").getValue().getContent()));
                affectedBusinessServices.addAll(searchPredecessors(vertex));
                cause = "application '" + e.getParm("applicationName").getValue().getContent() + "'";
            }

            if (EventConstants.NODE_DELETED_EVENT_UEI.equals(e.getUei())) {
                final GraphVertex vertex = m_stateMachine.getGraph().getVertexByReductionKey(String.format("uei.opennms.org/nodes/nodeDown::%d", e.getNodeid()));
                affectedBusinessServices.addAll(searchPredecessors(vertex));
                cause = "node '" + e.getNodeid() + "'";
            }

            if (EventConstants.INTERFACE_DELETED_EVENT_UEI.equals(e.getUei())) {
                final GraphVertex vertex = m_stateMachine.getGraph().getVertexByReductionKey(String.format("uei.opennms.org/nodes/interfaceDown::%d:%s", e.getNodeid(), e.getInterface()));
                affectedBusinessServices.addAll(searchPredecessors(vertex));
                cause = "interface '" + e.getNodeid() + "/" + e.getInterface() + "'";
            }

            if (EventConstants.SERVICE_DELETED_EVENT_UEI.equals(e.getUei())) {
                final GraphVertex vertex = m_stateMachine.getGraph().getVertexByReductionKey(String.format("uei.opennms.org/nodes/nodeLostService::%d:%s:%s", e.getNodeid(), e.getInterface(), e.getService()));
                affectedBusinessServices.addAll(searchPredecessors(vertex));
                cause = "service '" + e.getNodeid() + "/" + e.getInterface() + "/" + e.getService() + "'";
            }

            reloadConfigurationAt = System.currentTimeMillis() + RELOAD_DELAY;

            for (final BusinessService businessService : affectedBusinessServices) {
                final EventBuilder eventBuilder = new EventBuilder(EventConstants.BUSINESS_SERVICE_GRAPH_INVALIDATED, "bsmd");
                eventBuilder.addParam("businessServiceId", businessService.getId());
                eventBuilder.addParam("businessServiceName", businessService.getName());
                eventBuilder.addParam("cause", cause);
                final Event event = eventBuilder.getEvent();
                try {
                    m_eventIpcManager.send(event);
                } catch (EventProxyException ex) {
                    LOG.error("Cannot send event " + event.getUei(), ex);
                }
            }
        }
    }

    /**
     * Called when the configuration of one or more business services was changed.
     */
    private void handleConfigurationChanged() {
        if (m_verifyReductionKeys) {
            // The state machine makes certain assumptions about the reduction keys
            // associated with particular events. Since these are configurable, we may
            // want to verify that the actual values match our assumptions and bail if they don't
            verifyReductionKey(EventConstants.NODE_LOST_SERVICE_EVENT_UEI, "%uei%:%dpname%:%nodeid%:%interface%:%service%");
            verifyReductionKey(EventConstants.NODE_DOWN_EVENT_UEI, "%uei%:%dpname%:%nodeid%");
            verifyReductionKey(EventConstants.INTERFACE_DOWN_EVENT_UEI, "%uei%:%dpname%:%nodeid%:%interface%");
        }

        // Update the state machine with the latest list of business services
        m_template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                final List<BusinessService> businessServices = m_manager.getAllBusinessServices();
                LOG.debug("Adding {} business services to the state machine.", businessServices.size());
                m_stateMachine.setBusinessServices(businessServices);
            }
        });
    }

    private void verifyReductionKey(String uei, String expectedReductionKey) {
        List<org.opennms.netmgt.xml.eventconf.Event> eventsForUei = m_eventConfDao.getEvents(uei);
        if (eventsForUei == null) {
            LOG.warn("Could not find an event with uei '{}'.", uei);
            return;
        }
        if (eventsForUei.size() != 1) {
            LOG.warn("Could not find a unique event definition for uei '{}'.", uei);
            return;
        }
        if (eventsForUei.get(0).getAlarmData() == null) {
            LOG.warn("Could not find alarm data for event with uei '{}'.", uei);
            return;
        }
        AlarmData alarmData = eventsForUei.get(0).getAlarmData();
        if (!expectedReductionKey.equals(alarmData.getReductionKey())) {
            LOG.warn("Expected reduction key '{}' for uei '{}' but found '{}'.", expectedReductionKey, uei, alarmData.getReductionKey());
        }
    }

    /**
     * Called when the operational status of a business service was changed.
     */
    @Override
    public void handleBusinessServiceStateChanged(BusinessServiceGraph graph, BusinessService businessService, Status newStatus, Status prevStatus) {
        final OnmsSeverity newSeverity = SeverityMapper.toSeverity(newStatus);
        final OnmsSeverity prevSeverity = SeverityMapper.toSeverity(prevStatus);

        // Always send an serviceOperationalStatusChanged event
        EventBuilder ebldr = new EventBuilder(EventConstants.BUSINESS_SERVICE_OPERATIONAL_STATUS_CHANGED_UEI, NAME);
        addBusinessServicesAttributesAsEventParms(businessService, ebldr);
        ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_ID, businessService.getId());
        ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_NAME, businessService.getName());
        ebldr.addParam(EventConstants.PARM_PREV_SEVERITY_ID, prevSeverity.getId());
        ebldr.addParam(EventConstants.PARM_PREV_SEVERITY_LABEL, prevSeverity.getLabel());
        ebldr.addParam(EventConstants.PARM_NEW_SEVERITY_ID, newSeverity.getId());
        ebldr.addParam(EventConstants.PARM_NEW_SEVERITY_LABEL, newSeverity.getLabel());
        m_eventIpcManager.sendNow(ebldr.getEvent());

        // Generate a serviceProblem or a serviceProblemResolved event based on the current status
        if (newSeverity.isGreaterThan(OnmsSeverity.NORMAL)) {
            ebldr = new EventBuilder(EventConstants.BUSINESS_SERVICE_PROBLEM_UEI, NAME);
            addBusinessServicesAttributesAsEventParms(businessService, ebldr);
            ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_ID, businessService.getId());
            ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_NAME, businessService.getName());
            ebldr.setSeverity(newSeverity.toString());
            final List<GraphVertex> vertices = GraphAlgorithms.calculateRootCause(graph, graph.getVertexByBusinessServiceId(businessService.getId()));
            final String rootCause = vertices.stream().map(v -> {
                if (v.getBusinessService() != null) {
                    return "business service '" + v.getBusinessService().getName() + "'";
                } else if (v.getIpService() != null) {
                    return "IP service '" + v.getIpService().getNodeLabel() + "/" + v.getIpService().getIpAddress() + "/" + v.getIpService().getServiceName() + "'";
                } else if (v.getApplication() != null) {
                    return "application '" + v.getApplication().getApplicationName() + "'";
                } else {
                    return "reduction key '" + v.getReductionKey() + "'";
                }
            }).collect(Collectors.joining(", "));
            ebldr.addParam("rootCause", rootCause);
        } else {
            ebldr = new EventBuilder(EventConstants.BUSINESS_SERVICE_PROBLEM_RESOLVED_UEI, NAME);
            addBusinessServicesAttributesAsEventParms(businessService, ebldr);
            ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_ID, businessService.getId());
            ebldr.addParam(EventConstants.PARM_BUSINESS_SERVICE_NAME, businessService.getName());
        }
        m_eventIpcManager.sendNow(ebldr.getEvent());
    }

    /**
     * Adds all of the business services attributes as parameters to the given event builder.
     */
    private static void addBusinessServicesAttributesAsEventParms(BusinessService businessService, EventBuilder ebldr) {
        businessService.getAttributes().entrySet().stream()
            .forEach(attr -> {
                ebldr.addParam(attr.getKey(), attr.getValue());
            });
    }

    @EventHandler(uei = EventConstants.RELOAD_DAEMON_CONFIG_UEI)
    public void handleReloadEvent(Event e) {
        DaemonTools.handleReloadEvent(e, Bsmd.NAME, (event) -> handleConfigurationChanged());
    }

    @Override
    public void destroy() {
        LOG.info("Stopping bsmd...");
    }

    public void setEventIpcManager(EventIpcManager eventIpcManager) {
        m_eventIpcManager = eventIpcManager;
    }

    public EventIpcManager getEventIpcManager() {
        return m_eventIpcManager;
    }

    public void setEventConfDao(EventConfDao eventConfDao) {
        m_eventConfDao = eventConfDao;
    }

    public EventConfDao getEventConfDao() {
        return m_eventConfDao;
    }

    public void setTransactionTemplate(TransactionTemplate template) {
        m_template = template;
    }

    public TransactionTemplate getTransactionTemplate() {
        return m_template;
    }

    public void setVerifyReductionKeys(boolean verify) {
        m_verifyReductionKeys = verify;
    }

    public boolean getVerifyReductionKeys() {
        return m_verifyReductionKeys;
    }

    public void setBusinessServiceStateMachine(BusinessServiceStateMachine stateMachine) {
        m_stateMachine = stateMachine;
    }

    public BusinessServiceStateMachine getBusinessServiceStateMachine() {
        return m_stateMachine;
    }

}
