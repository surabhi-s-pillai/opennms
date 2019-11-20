/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019-2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
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

package org.opennms.netmgt.graph.enrichment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.opennms.netmgt.graph.api.enrichment.EnrichmentProcessor;
import org.opennms.netmgt.graph.api.enrichment.EnrichmentService;
import org.opennms.netmgt.graph.api.generic.GenericGraph;

public class DefaultEnrichmentService implements EnrichmentService {

    private final List<EnrichmentProcessor> enrichmentProcessors = new CopyOnWriteArrayList<>();

    @Override
    public GenericGraph enrich(GenericGraph graph) {
        if (graph != null) {
            final List<EnrichmentProcessor> actualProcessors = enrichmentProcessors.stream().filter(p -> p.canEnrich(graph)).collect(Collectors.toList());
            // TODO MVR this is probably very slow as the graph is rebuilt all the time. This should probably work on the builder instead
            GenericGraph enrichedGraph = graph;
            for (EnrichmentProcessor processor : actualProcessors) {
                enrichedGraph = processor.enrich(graph);
            }
            return enrichedGraph;
        }
        return null;
    }

    public void onBind(EnrichmentProcessor enrichmentProcessor, Map<String, String> props) {
        enrichmentProcessors.add(enrichmentProcessor);
    }

    public void onUnbind(EnrichmentProcessor enrichmentProcessor, Map<String, String> props) {
        enrichmentProcessors.remove(enrichmentProcessor);
    }

}