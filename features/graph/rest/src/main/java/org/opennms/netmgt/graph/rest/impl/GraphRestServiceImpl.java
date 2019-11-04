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

package org.opennms.netmgt.graph.rest.impl;

import java.util.List;
import java.util.Objects;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.json.JSONObject;
import org.opennms.netmgt.graph.api.ImmutableGraph;
import org.opennms.netmgt.graph.api.ImmutableGraphContainer;
import org.opennms.netmgt.graph.api.generic.GenericGraph;
import org.opennms.netmgt.graph.api.generic.GenericVertex;
import org.opennms.netmgt.graph.api.info.GraphContainerInfo;
import org.opennms.netmgt.graph.api.service.GraphService;
import org.opennms.netmgt.graph.rest.api.GraphRestService;
import org.opennms.netmgt.graph.rest.api.Query;
import org.opennms.netmgt.graph.rest.impl.renderer.JsonGraphRenderer;

public class GraphRestServiceImpl implements GraphRestService {

    private GraphService graphService;

    public GraphRestServiceImpl(GraphService graphService) {
        this.graphService = Objects.requireNonNull(graphService);
    }

    @Override
    public Response listContainerInfo() {
        final List<GraphContainerInfo> graphContainerInfos = graphService.getGraphContainerInfos();
        if (graphContainerInfos.isEmpty()) {
            return Response.noContent().build();
        }
        final String rendered = render(graphContainerInfos);
        return Response.ok(rendered).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    @Override
    public Response getContainer(String containerId) {
        final ImmutableGraphContainer container = graphService.getGraphContainer(containerId);
        if (container == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final String rendered = render(container);
        return Response.ok(rendered).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    @Override
    public Response getGraph(String containerId, String namespace) {
        final GenericGraph graph = graphService.getGraph(containerId, namespace);
        if (graph == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final String rendered = render(graph);
        return Response.ok(rendered).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    @Override
    public Response getView(String containerId, String namespace, Query query) {
        final GenericGraph graph = graphService.getGraph(containerId, namespace);
        if (graph == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if(query.getSzl() == null) {
            query.setSzl(Query.DEFAULT_SEMANTIC_ZOOM_LEVEL);
        }
        if (query.getSzl() < 0) {
            return Response
                .status(Response.Status.BAD_REQUEST)
                .entity(new JSONObject().put("error", "SZL must be >= 0 but was " + query.getSzl()).toString())
                .build();
        }
        if (query.getVerticesInFocus().isEmpty()) {
            query.setVerticesInFocus(graph.getDefaultFocus().getVertexIds());
        }
        final List<GenericVertex> focussedVertices = graph.resolveVertices(query.getVerticesInFocus());
        final GenericGraph view = graph.getView(focussedVertices, query.getSzl()).asGenericGraph();
        final JSONObject jsonView = new JsonGraphRenderer().convert(view);
        jsonView.put("focus", convert(query));
        jsonView.remove("defaultFocus"); // There shouldn't be a default focus
        return Response.ok(jsonView.toString()).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    private static JSONObject convert(Query query) {
        final JSONObject jsonQuery = new JSONObject();
        jsonQuery.put("szl", query.getSzl());
        jsonQuery.put("vertices", query.getVerticesInFocus());
        return jsonQuery;
    }

    private static String render(List<GraphContainerInfo> infos) {
        return new JsonGraphRenderer().render(infos);
    }

    private static String render(ImmutableGraphContainer graphContainer) {
        return new JsonGraphRenderer().render(graphContainer);
    }

    private static String render(ImmutableGraph graph) {
        return new JsonGraphRenderer().render(graph);
    }
}
